(ns patalyze.core
  (:require [patalyze.retrieval   :as retrieval]
            [riemann.client       :as r]
            [net.cgrand.enlive-html       :as html]
            [clojure.core.match   :refer (match)]
            [schema.core          :as s]
            [clojurewerkz.elastisch.rest          :as esr]
            [clojurewerkz.elastisch.rest.index    :as esi]
            [clojurewerkz.elastisch.query         :as q]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.bulk     :as esb]
            [clojurewerkz.elastisch.rest.response :as esresp]))

(def c (r/tcp-client {:host "127.0.0.1"}))

(defn union-re-patterns [& patterns]
  (re-pattern (apply str (interpose "|" (map #(str "(?:" % ")") patterns)))))

(def dtd-matcher
  (union-re-patterns #"us-patent-application-v4\d{1}-\d{4}-\d{2}-\d{2}\.dtd"
                     #"pap-v\d{2}-\d{4}-\d{2}-\d{2}\.dtd"))

(defn adjust-dtd-path [xml-str]
  "Because Strings are parsed with the project dir as root
  we need to fix the path to the DTD referenced in the XML"
  (clojure.string/replace xml-str
                          dtd-matcher
                          #(str "resources/parsedir/" %1)))

(defn parse [xml-str]
  "Reads a string and returns an xml zipper"
  (html/xml-resource
    (java.io.ByteArrayInputStream.
      (.getBytes
        (adjust-dtd-path xml-str)))))

(defn version-samples []
  "Find all xmls in the resources/patent_archives/ directory"
  (let [directory (clojure.java.io/file "resources/samples/")
        files (file-seq directory)]
    (zipmap [:v15 :v16 :v40 :v41 :v42 :v43]
            (map slurp (filter #(re-seq #".*\.xml" %) (map str files))))))

(defn test-parse-fn [f]
  (let [zipped (into {} (for [[k v] (version-samples)] [k (parse v)]))]
    (into {} (for [[k v] zipped] [k (f k v)]))))

; some utility functions to avoid duplication in paths for different versions
(defn prev-el [el coll]
  (let [ss (apply sorted-set coll)
        l  (subseq ss <= el)]
    (last l)))

(defn dispatch-version-path [version path-map]
  (let [dispatched-version (prev-el version (keys path-map))]
    (dispatched-version path-map)))


; TITLE
(defn invention-title [version xml-resource]
  (let [path (dispatch-version-path version {:v15 [:subdoc-bibliographic-information :technical-information :title-of-invention]
                                             :v40 [:us-bibliographic-data-application :invention-title]})
        title-tag (first (html/select xml-resource path))]
    (html/text title-tag)))

; DATES

; UNIQUE IDENTIFIER
(defn publication-identifier [version xml-resource]
  (let [paths (dispatch-version-path version {:v15 [:subdoc-bibliographic-information :> :document-id :*]
                                              :v40 [:us-bibliographic-data-application :publication-reference :document-id :*]})
        document-id (html/select xml-resource paths)
        tag-contents (html/texts document-id)]
    (clojure.string/join "-" (remove #(= % "US") tag-contents))))


; ABSTRACT
(defn invention-abstract [version xml-resource]
  (let [path (dispatch-version-path version {:v15 [:subdoc-abstract :paragraph]
                                             :v40 [:abstract]})
        abstract (first (html/select xml-resource path))]
    (clojure.string/trim (html/text abstract))))

(defn parse-inventor [version inventor-node]
  (let [paths   (dispatch-version-path version {:v15 [[:name :given-name] [:name :middle-name] [:name :family-name]]
                                                :v40 [[:addressbook :first-name] [:addressbook :last-name]]})
        nodes   (map #(first (html/select inventor-node %)) paths)
        fields  (html/texts nodes)]
    (clojure.string/join " " fields)))

(defn inventors [version xml-resource]
  (let [path (dispatch-version-path version {:v15 [:subdoc-bibliographic-information :inventors :> :*]
                                             :v40 [:us-bibliographic-data-application :parties :applicants :> :*]
                                             :v43 [:us-bibliographic-data-application :us-parties :inventors :> :*]})
        inventors (html/select xml-resource path)]
    (map #(parse-inventor version %)
       inventors)))


; ASSIGNEE
(defn orgname [version xml-resource]
  (let [path    (dispatch-version-path version {:v15 [:subdoc-bibliographic-information :correspondence-address :name-2]
                                                :v40 [:us-bibliographic-data-application :parties :correspondence-address :addressbook :name]
                                                :v41 [:us-bibliographic-data-application :assignees :orgname]})
        assignee (first (html/select xml-resource path))]
   (html/text assignee)))

; PUTTING IT TOGETHER
(defn detect-version [xml-str]
  (match [(apply str (re-seq dtd-matcher xml-str))]
     ["us-patent-application-v43-2012-12-04.dtd"] :v43
     ["us-patent-application-v42-2006-08-23.dtd"] :v42
     ["us-patent-application-v41-2005-08-25.dtd"] :v41
     ["us-patent-application-v40-2004-12-02.dtd"] :v40
     ["pap-v16-2002-01-01.dtd"]                   :v16
     ["pap-v15-2001-01-31.dtd"]                   :v15
     :else :not-recognized))

(defn patentxml->map [xml-str]
  (try
    (let [version      (detect-version xml-str)
          xml-resource (parse xml-str)
          parsed {:organization (orgname version xml-resource)
                  :inventors    (inventors version xml-resource)
                  :abstract     (invention-abstract version xml-resource)
                  :title        (invention-title version xml-resource)
                  :uid          (publication-identifier version xml-resource)
                  :plain-data   xml-str}]
      (do
        (r/send-event c {:ttl 20 :service "patalyze.parse"
                         :description (:uid parsed) :state "ok"})
        parsed))
  (catch org.xml.sax.SAXParseException e
     (r/send-event c {:ttl 20 :service "patalyze.parse"
                      :description xml-str :state "error"}))))

(defn read-file [xml-archive]
  "Reads one weeks patent archive and returns a seq of maps w/ results"
  (map patentxml->map (retrieval/read-and-split-from-zipped-xml xml-archive)))

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

;; BULK INSERTION
(defn prepare-bulk-op [patents]
  (esb/bulk-index
    (map #(assoc % :_index "patalyze_development"
                   :_type "patent"
                   :_id (:uid %)) patents)))

(defn bulk-insert [patents]
  (map #(esb/bulk (prepare-bulk-op %) :refresh true)
       (partition-all 2000 patents)))

(defn index-file [f]
  (bulk-insert (prepare-bulk-op (read-file f))))

(comment
  (esr/connect! "http://127.0.0.1:9200")
  (esd/delete-by-query-across-all-indexes-and-types (q/match-all))
  ;; creates an index with default settings and no custom mapping types
  (time (index-file (nth (patent-application-files) 4)))
  (esd/count "patalyze_development" "patent" (q/match-all))

  (esd/count "patalyze_development" "patent" (q/match-all))
  (esi/delete "patalyze_development")
  (esi/create "patalyze_development" :mappings cmapping)
  (esi/update-mapping "patalyze_development" "patent" :mapping cmapping)
  (esi/refresh "patalyze_development")


  (esd/search "patalyze_development" "patent" :query (q/term :inventors "Christopher D. Prest"))
  (esd/search "patalyze_development" "patent" :query {:term {:inventors "Daniel Francis Lowery"}})
  (esd/search "patalyze_development" "patent" :query (q/term :title "plastic"))

  (esd/search "patalyze_development" "patent" :query (q/term :inventors "Prest"))
  (esresp/total-hits (esd/search "patalyze_development" "patent" :query {:term {:title "plastic"}})))
