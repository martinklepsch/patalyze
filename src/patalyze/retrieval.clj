(ns patalyze.retrieval
  (:require [riemann.client :as r]
            [taoensso.carmine :as car :refer (wcar)]
            [net.cgrand.enlive-html :as html]))

(def c (r/tcp-client {:host "127.0.0.1"}))
(defmacro wcar* [& body] `(car/wcar nil ~@body))

(def ^:dynamic *applications-biblio-url*
  "http://www.google.com/googlebooks/uspto-patents-applications-biblio.html")

(defn fetch-url [url]
  (html/html-resource (java.net.URL. url)))

(defn archive-links []
  (let [links (html/select (fetch-url *applications-biblio-url*) [:body :> :a])
        catalog (zipmap (map #(clojure.string/trim (html/text %)) links) (map #(:href (:attrs %)) links))]
    catalog))

(defn patent-application-files []
  "Find all xmls in the resources/patent_archives/ directory"
  (let [directory (clojure.java.io/file "resources/applications/")
        files (file-seq directory)]
     (sort-by
       #(apply str (re-seq #"\d{8}" %))
        (filter #(re-seq #"\.zip" %) (map str files)))))

(defn not-downloaded []
  (let [week-ids   (map #(clojure.string/replace % #"\.zip$" "") (keys (archive-links)))
        on-fs?     (fn [wkid] (some #(re-seq (re-pattern wkid) %) (patent-application-files)))
        not-on-fs  (select-keys (archive-links) (map #(str % ".zip") (remove on-fs? week-ids)))]
    (sort-by
      #(apply str (re-seq #"\d{8}" (key %)))
      not-on-fs)))

(defn copy-uri-to-file [[file uri]]
  (with-open [in (clojure.java.io/input-stream uri)
              out (clojure.java.io/output-stream (str "resources/applications/" file))]
    (do
      (clojure.java.io/copy in out)
      (r/send-event c {:ttl 300 :service "patalyze.retrieval"
                       :tag "downloaded" :description file
                       :state "ok"}))))

(defn find-xml [zipfile]
  (first
    (filter
      #(re-seq #"\.xml" (.getName %))
      (enumeration-seq (.entries zipfile)))))

(defn split-file [readable]
  "Splits archive file into strings at xml statements"
  (let [fseq (with-open [rdr (clojure.java.io/reader readable)] (reduce conj [] (line-seq rdr)))
        xml-heads ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>" "<?xml version=\"1.0\"?>"]
        segments (partition 2 (partition-by #(some #{%} xml-heads) fseq))
        patents-xml (map #(apply str (concat (first %) (second %))) segments)]
    patents-xml))

(defn read-and-split-from-zipped-xml [file-name]
  (with-open [zip (java.util.zip.ZipFile. file-name)
              xml (.getInputStream zip (find-xml zip))]
    (split-file xml)))

;; (clojure.java.io/reader (get-xml-file-contents "resources/applications/ipab20130110_wk02.zip"))
;; (count (read-and-split-from-zipped-xml (first (patent-application-files))))
