(ns patalyze.index
  (:require [patalyze.retrieval   :as retrieval]
            [patalyze.parser      :as parser]
            [patalyze.config      :refer [c es env]]
            [riemann.client       :as r]
            [schema.core          :as s]
            [taoensso.timbre      :as timbre :refer (log  trace  debug  info  warn  error)]
            [clojurewerkz.elastisch.rest          :as esr]
            [clojurewerkz.elastisch.rest.index    :as esi]
            [clojurewerkz.elastisch.query         :as q]
            [clojurewerkz.elastisch.aggregation   :as a]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.bulk     :as esb]
            [clojurewerkz.elastisch.rest.response :as esresp])
  (:import (java.util.concurrent TimeUnit Executors)))

(def ^:dynamic *bulk-size* 3000)

; (def patent-count-notifier
;   (.scheduleAtFixedRate (Executors/newScheduledThreadPool 1)
;     #(r/send-event @c {:ttl 20 :service "patalyze.index/document-count"
;                       :state "ok" :metric (:count (esd/count @es "patalyze_development" "patent" (q/match-all)))})
;     0 10 TimeUnit/SECONDS))

(defn version-samples []
  "Find all xmls in the resources/patent_archives/ directory"
  (let [directory (clojure.java.io/file "resources/samples/")
        files (file-seq directory)]
    (zipmap [:v15 :v16 :v40 :v41 :v42 :v43]
            (map slurp (filter #(re-seq #".*\.xml" %) (map str files))))))

(defn test-parse-fn [f]
  "use like this: (test-parse-fn parser/filing-date)"
  (let [zipped (into {} (for [[k v] (version-samples)] [k (parser/parse v)]))]
    (into {} (for [[k v] zipped] [k (f k v)]))))

(defn read-file [xml-archive]
  "Reads one weeks patent archive and returns a seq of maps w/ results"
  (let [snippets (retrieval/read-and-split-from-zipped-xml xml-archive)]
    (map-indexed
      #(try (parser/patentxml->map %2)
        (catch Exception e
          (error xml-archive "::" %1 "\n" e)
          nil))
      snippets)))

;; it seems that the parsing is actually not the bottleneck and that elasticsearch
;; is causing the trouble now. this fn should give me a realistic parsing-rate because it
;; ignores the elasticsearch part
(defn read-file! [f]
  (doseq [p (read-file f)]
    (r/send-event @c {:ttl 20 :service "patalyze.parse"
                     :description (:uid p) :state "ok"})))

;; BULK INSERTION
(def ^:private special-operation-keys
  [:_index :_type :_id :_routing :_percolate :_parent :_timestamp :_ttl])

(defn upsert-operation [doc]
  {"update" (select-keys doc special-operation-keys)})

(defn upsert-document [doc]
  {:doc (dissoc doc :_index :_type)
   :doc_as_upsert true})

(defn bulk-upsert
  "generates the content for a bulk insert operation"
  ([documents]
     (let [operations (map upsert-operation documents)
           documents  (map upsert-document  documents)]
       (interleave operations documents))))

(defn prepare-bulk-op [patents]
  (bulk-upsert
    (map #(assoc % :_index "patalyze_development"
                   :_type "patent"
                   :_id (:uid %)) patents)))

(defn partitioned-bulk-op [patents]
  (doseq [pat (partition-all *bulk-size* patents)]
    (let [res (esb/bulk @es (prepare-bulk-op pat))]
      (r/send-event @c {:ttl 20 :service "patalyze.bulk"
                        :description (str (count pat) " patents upserted")
                        :metric (:took res) :state (if (:errors res) "error" "ok")}))))

(def PatentApplication
  {:uid s/Str
   :title s/Str
   :abstract s/Str
   :inventors  [(s/one s/Str "inventor")
                s/Str]
   :assignees [{:orgname s/Str
               :role s/Str }]})

; ELASTISCH
(def cmapping
  { "patent"
    { :properties
      { :inventors { :type "string" :index "not_analyzed" }
        :publication-date { :type "date" }
        :filing-date      { :type "date" }}}})

;; using analyzer :analyzer "whitespace" we can search for parts of the inventors name
;; with :index "not_analyzed"

;; INDEX WITH ELASTISCH
(defn index-files [files]
  (partitioned-bulk-op
    (flatten (map read-file files))))

;; (def some-patents
;;   (read-file (first (retrieval/patent-application-files))))

(defn create-elasticsearch-mapping []
  (esi/create @es "patalyze_development" :mappings cmapping))

(defn patent-count []
  (esd/count @es "patalyze_development" "patent" (q/match-all)))

(defn clear-patents []
  (esd/delete-by-query-across-all-indexes-and-types @es (q/match-all)))

(defn count-for-range [from to]
  (esresp/total-hits (esd/search @es "patalyze_development" "patent"
                                 :query (q/range :publication-date :from from :to to))))

(defn count-patents-in-archives []
  (reduce + (map #(count (retrieval/read-and-split-from-zipped-xml %))
                 (retrieval/patent-application-files))))

(defn merge-mapfile! [file map-to-merge]
  (if (.exists (clojure.java.io/as-file file))
    (spit file
      (merge
        (read-string (slurp file))
        map-to-merge))
    (spit file map-to-merge)))

(defn update-archive-stats-file! [archives]
  (let [stats-file   (str (deref (env :data-dir)) "/archive-stats.edn")]
    (merge-mapfile! stats-file
      (into {}
        (for [f archives]
           {(apply str (re-seq #"\d{8}" f)) (count (retrieval/read-and-split-from-zipped-xml f))})))))

;; (let [ks (keys (read-string (slurp (str (deref (env :data-dir) "/archive-stats.edn"))))
;;       fs (retrieval/patent-application-files)]
;;   (filter #(some #{(apply str (re-seq #"\d{8}" %))} ks) fs))

(defn archive-stats []
  (let [stats-file (str (deref (env :data-dir)) "/archive-stats.edn")
        on-disk    (retrieval/patent-application-files)]
    (if (not (.exists (clojure.java.io/as-file stats-file)))
      (do
        (update-archive-stats-file! on-disk)
        (archive-stats))
      (do
        (update-archive-stats-file!
          (remove #(some #{(apply str (re-seq #"\d{8}" %))}
                         (keys (read-string (slurp stats-file))))
                  on-disk))
        (read-string (slurp stats-file))))))

(defn database-stats []
  (let [agg (esd/search @es "patalyze_development" "patent"
                        { :query (q/match-all)
                          :aggregations {:dates (a/terms "publication-date" {:size 0})}})
        pub-dates (get-in agg [:aggregations :dates :buckets])]
    (merge
      (into {}
        (for [f (retrieval/patent-application-files)]
           {(apply str (re-seq #"\d{8}" f)) 0}))
      (into {}
        (for [p pub-dates]
          {(apply str (re-seq #"\d{8}" (:key p))) (:doc_count p)})))))

(defn index-integrity-stats []
  (merge-with #(zipmap [:archive :database] %&) (archive-stats) (database-stats)))

(defn archive-for-date [date-string]
  (first (filter #(= (apply str (re-seq #"\d{8}" %)) date-string)
                  (retrieval/patent-application-files))))

(defn incompletely-indexed-archives []
  (let [stats      (index-integrity-stats)
        incomplete (select-keys stats (for [[k v] stats :when (not= (:database v) (:archive v))] k))]
  (into {}
    (for [[date s] incomplete]
      {(archive-for-date date) s}))))

(defn patents-by-inventor [inventor]
  (map :_source
    (esd/scroll-seq @es
      (esd/search @es "patalyze_development" "patent"
                  :query (q/match :inventors inventor :operator :and)
                  :search_type "query_then_fetch"
                  :scroll "1m"
                  :size 20))))

(defn patents-by-org [org]
  (map :_source
    (esd/scroll-seq @es
      (esd/search @es "patalyze_development" "patent"
                  :query (q/match :organization org :operator :and)
                  :sort :publication-date
                  :scroll "1m"
                  :size 20))))

(comment
  (map (comp :organization :_source)
    (esd/scroll-seq @es
      (esd/search @es "patalyze_development" "patent"
        :query (q/match :organization "Apple Inc."))))

  (count (map :organization
    (patents-by-org-and-date "Apple Inc." "20140612")))
  (count (map :organization
    (patents-by-org-and-date "Apple Inc." "20140619")))

  (set (map :organization
    (patents-by-inventor "Christopher D. Prest")))
  (count
    (patents-by-org-and-date "Apple Inc." "20140612"))
  (esi/update-mapping @es "patalyze_development" "patent" :mapping cmapping) 

  (connect-elasticsearch)
  (:count (esd/count "patalyze_development" "patent" (q/match-all)))
  (retrieval/patent-application-files)
  (apply queue-archive (retrieval/patent-application-files))
  (patent-count)
  (esd/delete-by-query-across-all-indexes-and-types (q/match-all))
  (esd/search "patalyze_development" "patent" :query (q/match-all))
  (esd/delete-by-query-across-all-indexes-and-types (q/match-all))
  ;; creates an index with default settings and no custom mapping types
  (time (index-file (nth (patent-application-files) 4)))
  (esd/count "patalyze_development" "patent" (q/match-all))
  (esd/count "patalyze_development" "patent" (q/match-all))
  (esi/update-mapping "patalyze_development" "patent" :mapping cmapping)
  (esi/refresh "patalyze_development")
  (esd/search "patalyze_development" "patent" :query (q/term :inventors "Christopher D. Prest"))
  (esd/search "patalyze_development" "patent" :query {:term {:inventors "Daniel Francis Lowery"}})
  (esd/search "patalyze_development" "patent" :query (q/term :title "plastic"))
  (esd/search "patalyze_development" "patent" :query (q/term :inventors "Prest"))
  (esresp/total-hits (esd/search "patalyze_development" "patent" :query {:term {:title "plastic"}})))
