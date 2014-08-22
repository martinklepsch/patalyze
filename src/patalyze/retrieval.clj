(ns patalyze.retrieval
  (:require [patalyze.storage   :refer [list-applications]]
            [environ.core       :refer [env]]
            [net.cgrand.enlive-html :as html])
  (:import (java.util.zip ZipFile)))

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
  (let [directory (clojure.java.io/file (str (env :data-dir) "/applications/"))
        files     (filter #(re-seq #"\.zip" %) (map str (file-seq directory)))]
     (sort-by
       #(apply str (re-seq #"\d{6}" %))
       files)))

(defn applications-by-year []
  (let [directory (clojure.java.io/file (str (env :data-dir) "/applications/"))
        files     (filter #(re-seq #"\.zip" %) (map str (file-seq directory)))
        year      #(keyword (last (first (re-seq #"pab(20\d{2})" (str %)))))]
    (group-by year files)))

(defn not-downloaded []
  (let [week-ids   (map #(clojure.string/replace % #"\.zip$" "") (keys (archive-links)))
        on-fs?     (fn [wkid] (some #(re-seq (re-pattern wkid) %) (patent-application-files)))
        not-on-fs  (select-keys (archive-links) (map #(str % ".zip") (remove on-fs? week-ids)))]
    (sort-by
      #(apply str (re-seq #"\d{6}" (key %)))
      not-on-fs)))

(defn copy-uri-to-file [[file uri]]
  (with-open [in (clojure.java.io/input-stream uri)
              out (clojure.java.io/output-stream (str (env :data-dir) "/applications/" file))]
    (do
      (clojure.java.io/copy in out))))

(defn fetch-missing []
  ;; TODO probably doseq is more appropriate here
  (map copy-uri-to-file (not-downloaded)))

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

(defn extract-archive-identifier [from-str]
  (last (first (re-seq #"(\d{8}_wk\d{2})." from-str))))

(defn identifier-contained? [l-ident ident]
  (if ((set l-ident) ident)
    true
    false))

(defn status []
  ;; https://github.com/flatland/useful/blob/develop/src/flatland/useful/state.clj#L78
  ;; Look into the stuff above to cache archive-links, patent-application-files and
  ;; list-applications functions
  (let [links       (archive-links)
        files       (map extract-archive-identifier (patent-application-files))
        s3-objects  (map extract-archive-identifier (list-applications))
        downloaded? (partial identifier-contained? files)
        on-s3?      (partial identifier-contained? s3-objects)]
    (into {}
          (for [k (map extract-archive-identifier (keys links))
                v (vals links)]
            (let [ident (extract-archive-identifier k)]
              {k {:url v :on-disk (downloaded? k) :on-s3 (on-s3? k)}})))))

(defn where [map key value]
  (into {}
        (filter #(= value (key (val %)))
                map)))

;; (where (status) :on-disk true)

;; 1. get all archive identifiers from website
;; 2. tag them with status booleans (downloaded, on-S3)
;; 3. make them queriable with substrings of archive identifiers like "201401"

(comment
  ;; download files matching a certain string
  (let [files (filter (fn [[file-name uri]] (re-seq #"20021219" file-name)) (not-downloaded))]
    (map copy-uri-to-file files)))

;; (clojure.java.io/reader (get-xml-file-contents "resources/applications/ipab20130110_wk02.zip"))
;; (count (read-and-split-from-zipped-xml (first (patent-application-files))))
