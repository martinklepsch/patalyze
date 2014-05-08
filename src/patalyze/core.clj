(ns patalyze.core
  (:require [patalyze.retrieval   :as retrieval]
            [patalyze.parser      :as parser]
            [riemann.client       :as r]
            [schema.core          :as s]
            [taoensso.carmine :as car :refer (wcar)]
            [clojurewerkz.elastisch.rest          :as esr]
            [clojurewerkz.elastisch.rest.index    :as esi]
            [clojurewerkz.elastisch.query         :as q]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.bulk     :as esb]
            [clojurewerkz.elastisch.rest.response :as esresp]))

(def c (r/tcp-client {:host "127.0.0.1"}))
(defmacro wcar* [& body] `(car/wcar nil ~@body))

(defn version-samples []
  "Find all xmls in the resources/patent_archives/ directory"
  (let [directory (clojure.java.io/file "resources/samples/")
        files (file-seq directory)]
    (zipmap [:v15 :v16 :v40 :v41 :v42 :v43]
            (map slurp (filter #(re-seq #".*\.xml" %) (map str files))))))

(defn test-parse-fn [f]
  (let [zipped (into {} (for [[k v] (version-samples)] [k (parser/parse v)]))]
    (into {} (for [[k v] zipped] [k (f k v)]))))

(defn unparsed-files []
  (let [parsed (wcar* (car/smembers :parsed-archives))
        files  (retrieval/patent-application-files)]
    (remove (set parsed) files)))


(defn read-file [xml-archive]
  "Reads one weeks patent archive and returns a seq of maps w/ results"
  (do
    (wcar* (car/sadd :parsed-archives xml-archive))
    (map parser/patentxml->map
         (retrieval/read-and-split-from-zipped-xml xml-archive))))

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
      { :inventors { :type "string" :index "not_analyzed" }}}})
;; using analyzer :analyzer "whitespace" we can search for parts of the inventors name
;; with :index "not_analyzed"

(defn index-file [f]
  (bulk-insert (prepare-bulk-op (read-file f))))

(defn connect-elasticsearch []
  (esr/connect! "http://127.0.0.1:9200"))

(defn create-elasticsearch-mapping []
  (esi/create "patalyze_development" :mappings cmapping))

;; BULK INSERTION
(defn prepare-bulk-op [patents]
  (esb/bulk-index
    (map #(assoc % :_index "patalyze_development"
                   :_type "patent"
                   :_id (:uid %)) patents)))

(defn bulk-insert [patents]
  (map #(esb/bulk (prepare-bulk-op %) :refresh true)
       (partition-all 2000 patents)))

(comment
  (esd/delete-by-query-across-all-indexes-and-types (q/match-all))
  ;; creates an index with default settings and no custom mapping types
  (time (index-file (nth (patent-application-files) 4)))
  (esd/count "patalyze_development" "patent" (q/match-all))

  (esd/count "patalyze_development" "patent" (q/match-all))
  (esi/delete "patalyze_development")
  (esi/update-mapping "patalyze_development" "patent" :mapping cmapping)
  (esi/refresh "patalyze_development")


  (esd/search "patalyze_development" "patent" :query (q/term :inventors "Christopher D. Prest"))
  (esd/search "patalyze_development" "patent" :query {:term {:inventors "Daniel Francis Lowery"}})
  (esd/search "patalyze_development" "patent" :query (q/term :title "plastic"))

  (esd/search "patalyze_development" "patent" :query (q/term :inventors "Prest"))
  (esresp/total-hits (esd/search "patalyze_development" "patent" :query {:term {:title "plastic"}})))
