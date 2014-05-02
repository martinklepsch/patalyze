(ns patalyze.core
  (:require [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip :as dzip]
            [clojure.data.zip.xml :as zf]
            [clojure.core.match :refer (match)]
            [schema.core :as s]
            [clojurewerkz.elastisch.rest          :as esr]
            [clojurewerkz.elastisch.rest.index    :as esi]
            [clojurewerkz.elastisch.query         :as q]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.bulk     :as esb]
            [clojurewerkz.elastisch.rest.response :as esresp]))

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

(defn str->zipper [xml-str]
  "Reads a string and returns an xml zipper"
  (zip/xml-zip
    (xml/parse
      (java.io.ByteArrayInputStream.
        (.getBytes
          (adjust-dtd-path xml-str))))))

(defn version-samples []
  "Find all xmls in the resources/patent_archives/ directory"
  (let [directory (clojure.java.io/file "resources/samples/")
        files (file-seq directory)]
    (zipmap [:v15 :v16 :v40 :v41 :v42 :v43]
            (map slurp (filter #(re-seq #".*\.xml" %) (map str files))))))

(defn patent-application-files []
  "Find all xmls in the resources/patent_archives/ directory"
  (let [directory (clojure.java.io/file "resources/applications/")
        files (file-seq directory)]
     (sort-by
       #(apply str (re-seq #"\d{8}" %))
        (filter #(re-seq #"pab.*\.xml" %) (map str files)))))

(defn split-file [file]
  "Splits archive file into strings at xml statements"
  (let [fseq (with-open [rdr (clojure.java.io/reader file)] (reduce conj [] (line-seq rdr)))
        xml-head "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        segments (partition 2 (partition-by #(= % xml-head) fseq))
        patents-xml (map #(apply str (concat (first %) (second %))) segments)]
    patents-xml))

(defn dispatch-version-path [version path-map]
  (let [dispatched-version (prev-el version (keys path-map))]
    (dispatched-version path-map)))

; some utility functions to avoid duplication in paths for different versions
(defn prev-el [el coll]
  (cond
    (some #{el} coll) el
    :else             (last (first (partition-by #(= el %) (sort (conj coll el)))))))

; TITLE
(defn invention-title [version xml-zipper]
  (let [path (dispatch-version-path version {:v15 [:subdoc-bibliographic-information :technical-information :title-of-invention]
                                             :v40 [:us-bibliographic-data-application :invention-title]})
        title-tag (apply zf/xml1-> xml-zipper path)]
    (zf/text title-tag)))

; DATES

; UNIQUE IDENTIFIER
(defn publication-identifier [xml-zipper]
  (let [get-in-pubref #(zf/xml1-> xml-zipper
                       :us-bibliographic-data-application
                       :publication-reference
                       :document-id
                       %
                       zf/text)]
    (str
      (get-in-pubref :country)
      (get-in-pubref :doc-number)
      (get-in-pubref :kind)
      (get-in-pubref :date))))

; ABSTRACT
(defn invention-abstract [version xml-zipper]
  (let [path (dispatch-version-path version {:v15 [:subdoc-abstract :paragraph]
                                             :v40 [:abstract]})
        abstract (apply zf/xml1-> xml-zipper path)]
    (zf/text abstract)))

;; INVENTORS
;; (reduce-kv (fn [out k v] (into out (map (fn [x] [k x]) v))) [] {:name {:first-name [:a-name :last-name]}})
;; [:document-id [:country :doc-number :date :kind] zf/text]
;; (let [[first more] [:name [:first-name [:test :a]]]] (for [x more] [first x]))
;; (flatten [:name [:first-name [:test :a]]])
;; [:name :first-name :test] [:name :first-name :a]
;; [[:name :first-name] [:name :last-name]]

(defn parse-inventor [version inventor-node]
  (let [paths   (dispatch-version-path version {:v15 [[:name :given-name] [:name :middle-name] [:name :family-name]]
                                                :v40 [[:addressbook :first-name] [:addressbook :last-name]]})
        nodes   (map #(apply zf/xml1-> inventor-node %) paths)
        fields  (map zf/text nodes)]
    (clojure.string/join " " fields)))

(defn inventors [version xml-zipper]
  (let [path (dispatch-version-path version {:v15 [:subdoc-bibliographic-information :inventors]
                                             :v40 [:us-bibliographic-data-application :parties :applicants]
                                             :v43 [:us-bibliographic-data-application :us-parties :inventors]})
        inventors (apply zf/xml-> xml-zipper path)]
    (map #(parse-inventor version %)
       (apply dzip/children-auto inventors))))

;; (map :inventors (map patentxml->map (version-samples)))

; ASSIGNEE
(defn assignees [xml-zipper]
  (zf/xml-> xml-zipper
            :us-bibliographic-data-application
            :assignees
            dzip/children-auto))

(defn assignee->map [assignee-node]
  {:orgname (zf/xml1-> assignee-node :orgname zf/text)
   :role (zf/xml1-> assignee-node :role zf/text)})

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
  (let [version    (detect-version xml-str)
        xml-zipper (str->zipper xml-str)]
    {:assignees (map assignee->map (assignees xml-zipper))
     :inventors (inventors version xml-zipper)
     :abstract (invention-abstract version xml-zipper)
     :title (invention-title version xml-zipper)
     :uid (publication-identifier xml-zipper)}))

(defn read-file [xml-archive]
  "Reads one weeks patent archive and returns a seq of maps w/ results"
  (map patentxml->map (split-file xml-archive)))

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
