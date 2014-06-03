(ns patalyze.core
  (:require [patalyze.retrieval   :as retrieval]
            [patalyze.parser      :as parser]
            [riemann.client       :as r]
            [schema.core          :as s]
            [environ.core         :refer [env]]
            [clojurewerkz.elastisch.rest          :as esr]
            [clojurewerkz.elastisch.rest.index    :as esi]
            [clojurewerkz.elastisch.query         :as q]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.bulk     :as esb]
            [clojurewerkz.elastisch.rest.response :as esresp])
  (:import (java.util.concurrent TimeUnit Executors)))

(def c  (r/tcp-client {:host (env :db-private)}))
(def es (esr/connect (str "http://" (env :db-private) ":9200")))

(def ^:dynamic *bulk-size* 3000)

(def patent-count-notifier
  (.scheduleAtFixedRate (Executors/newScheduledThreadPool 1)
    #(r/send-event c {:ttl 20 :service "patalyze.index/document-count"
                      :state "ok" :metric (:count (esd/count "patalyze_development" "patent" (q/match-all)))})
    0 10 TimeUnit/SECONDS))

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


;; (defn unparsed-files []
;;   (let [parsed (wcar* (car/smembers :parsed-archives))
;;         files  (retrieval/patent-application-files)]
;;     (remove (set parsed) files)))

(defn read-file [xml-archive]
  "Reads one weeks patent archive and returns a seq of maps w/ results"
  (map parser/patentxml->map
       (retrieval/read-and-split-from-zipped-xml xml-archive)))

;; it seems that the parsing is actually not the bottleneck and that elasticsearch
;; is causing the trouble now. this fn should give me a realistic parsing-rate because it
;; ignores the elasticsearch part
(defn read-file! [f]
  (doseq [p (read-file f)]
    (r/send-event c {:ttl 20 :service "patalyze.parse"
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
    (let [res (esb/bulk es (prepare-bulk-op pat))]
      (r/send-event c {:ttl 20 :service "patalyze.bulk"
                       :description (str *bulk-size* "patents upserted")
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
  (esi/create es "patalyze_development" :mappings cmapping))

(defn patent-count []
  (esd/count es "patalyze_development" "patent" (q/match-all)))

(defn clear-patents []
  (esd/delete-by-query-across-all-indexes-and-types es (q/match-all)))

;; (esi/delete es "patalyze_development")
;; (create-elasticsearch-mapping)
;; (clear-patents)
;; (patent-count)
;; (index-files (take 6 (retrieval/patent-application-files)))
(defn count-for-range [from to]
  (esresp/total-hits (esd/search es "patalyze_development" "patent"
                                 :query (q/range :publication-date :from from :to to))))
;; { "index" { "number_of_replicas" 0 } }
;; (pmap index-files (partition-all 70 (retrieval/patent-application-files)))))

(defn count-patents-in-archives []
  (reduce + (map #(count (retrieval/read-and-split-from-zipped-xml %))
                 (retrieval/patent-application-files))))


(comment
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
