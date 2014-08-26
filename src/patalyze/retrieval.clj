(ns patalyze.retrieval
  (:require [patalyze.storage   :refer [list-applications]]
            [environ.core       :refer [env]]
            [net.cgrand.enlive-html :as html])
  (:import (java.util.zip ZipFile)))

(def ^:dynamic *applications-biblio-url*
  "http://www.google.com/googlebooks/uspto-patents-applications-biblio.html")

(def archive-dir
  (str (env :data-dir) "/applications/"))
(def cache-dir
  (str (env :data-dir) "/cache/applications/"))

(defn fetch-url [url]
  (html/html-resource (java.net.URL. url)))

(defn archive-links []
  (let [links (html/select (fetch-url *applications-biblio-url*) [:body :> :a])
        catalog (zipmap (map #(clojure.string/trim (html/text %)) links) (map #(:href (:attrs %)) links))]
    catalog))

(defn patent-application-files []
  "Find all xmls in the resources/patent_archives/ directory"
  (let [directory (clojure.java.io/file archive-dir)
        files     (filter #(re-seq #"\.zip" %) (map str (file-seq directory)))]
     (sort-by
       #(apply str (re-seq #"\d{6}" %))
       files)))

(defn cached-applications []
  (let [directory (clojure.java.io/file cache-dir)
        files     (filter #(re-seq #"\.edn.gz" %) (map str (file-seq directory)))]
     (sort-by
       #(apply str (re-seq #"\d{6}" %))
       files)))

(defn extract-archive-identifier [from-str]
  (last (first (re-seq #"(\d{8}_wk\d{2})." from-str))))

(defn identifier-contained? [l-ident ident]
  (if ((set l-ident) ident) true false))

(defn status []
  ;; https://github.com/flatland/useful/blob/develop/src/flatland/useful/state.clj#L78
  ;; Look into the stuff above to cache archive-links, patent-application-files and
  ;; list-applications functions
  (let [links       (archive-links)
        files       (map extract-archive-identifier (patent-application-files))
        s3-objects  (map extract-archive-identifier (list-applications))
        cached      (map extract-archive-identifier (cached-applications))
        downloaded? (partial identifier-contained? files)
        on-s3?      (partial identifier-contained? s3-objects)
        cached?     (partial identifier-contained? cached)]
    (into {}

          (map (fn [[k v]]
                 ;; (for [k (map extract-archive-identifier (keys links))
                 ;;       v (vals links)]
                 {(extract-archive-identifier k) {:uri v :on-disk (downloaded? k) :on-s3 (on-s3? k)
                     :cached (cached? k)}})
               links))))

(defn map-subset? [sub, super]
  (= sub (select-keys super (keys sub))))

(defn where
  "Query a map with optional prefix to limit keys

   Examples:
    (where (status) {:on-disk true :on-s3 false})
    (where (status) {:on-s3 false} \"2013\")"
  ([map submap]
     (where map submap nil))
  ([map submap prefix]
     (into {}
           (filter #(and (if prefix (.startsWith (key %) prefix) true)
                         (map-subset? submap (val %)))
                   map))))

(defn copy-archive-from-uri [uri]
  (let [ext   (str "." (last (clojure.string/split uri #"\.")))
        ident (extract-archive-identifier uri)]
    (with-open [in    (clojure.java.io/input-stream uri)
                out   (clojure.java.io/output-stream (str archive-dir ident ext))]
      (clojure.java.io/copy in out))))

(defn fetch-missing []
  (map copy-archive-from-uri (map #(:uri (val %)) (where (status) {:on-disk false}))))

(defn find-xml [zipfile]
  (first
    (filter
      #(re-seq #"\.xml" (.getName %))
      (enumeration-seq (.entries zipfile)))))

(defn split-file [readable]
  "Splits archive file into strings at xml statements"
  (let [fseq (with-open [rdr (clojure.java.io/reader readable)] (reduce conj [] (line-seq rdr)))
        xml-heads ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>" "<?xml version=\"1.0\"?>"
                   "<?xml version=\"1.0\" encoding=\"UTF-8\"?><?xml-stylesheet href=\"specif.xsl\" type=\"text/xsl\"?>"]
        segments (partition 2 (partition-by #(some #{%} xml-heads) fseq))
        patents-xml (map #(apply str (concat (first %) (second %))) segments)]
    patents-xml))

(defn read-and-split-from-zipped-xml [file-name]
  (with-open [zip (ZipFile. file-name)
              xml (.getInputStream zip (find-xml zip))]
    (split-file xml)))

(comment
  ;; download files matching a certain string
  (let [files (filter (fn [[file-name uri]] (re-seq #"20021219" file-name)) (not-downloaded))]
    (map copy-uri-to-file files)))

;; (clojure.java.io/reader (get-xml-file-contents "resources/applications/ipab20130110_wk02.zip"))
;; (count (read-and-split-from-zipped-xml (first (patent-application-files))))
