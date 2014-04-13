(ns patalyze.core
  (:require [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip :as dzip]
            [clojure.data.zip.xml :as zf]
            [clojurewerkz.elastisch.rest          :as esr]
            [clojurewerkz.elastisch.rest.index    :as esi]
            [clojurewerkz.elastisch.query         :as q]
            [clojurewerkz.elastisch.rest.document :as esd]))

(defn file->zipper [uri-str]
  (->> (xml/parse uri-str)
       zip/xml-zip))

(defn patent-files []
  (let [directory (clojure.java.io/file "resources")
        files (file-seq directory)]
     (filter (fn [f] (re-find #"\.patent\.xml" f)) (map str files))))

(defn read-files [files]
  (map #(patentxml->map (file->zipper %))
       files))

(def patents
  (read-files (patent-files)))

(last patents)

; TITLE
(defn invention-title [xml-zipper]
  (zf/xml1-> xml-zipper
             :us-bibliographic-data-application
             :invention-title
             zf/text))
; ABSTRACT
(defn invention-abstract [xml-zipper]
  (zf/xml1-> xml-zipper
             :abstract
             zf/text))

; INVENTORS
(defn inventors [xml-zipper]
  (zf/xml-> xml-zipper
            :us-bibliographic-data-application
            :us-parties
            :inventors
            dzip/children-auto))

(defn inventor->map [inventor-node]
  (str (zf/xml1-> inventor-node :addressbook :first-name zf/text) " "
       (zf/xml1-> inventor-node :addressbook :last-name zf/text)))
;;   {:name (str (zf/xml1-> inventor-node :addressbook :first-name zf/text)
;;               (zf/xml1-> inventor-node :addressbook :last-name zf/text))
;;    :address (str (zf/xml1-> inventor-node :addressbook :address :city zf/text) ", "
;;                  (zf/xml1-> inventor-node :addressbook :address :state zf/text) ", "
;;                  (zf/xml1-> inventor-node :addressbook :address :country zf/text))})

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
(defn patentxml->map [xml-zipper]
  {:assignees (map assignee->map (assignees xml-zipper))
   :inventors (map inventor->map (inventors xml-zipper))
   :abstract (invention-abstract xml-zipper)
   :title (invention-title xml-zipper)})

(patentxml->map (file->zipper (first (patent-files))))
(assignee->map (first (assignees (file->zipper (first patent-files)))))
(map inventor-name (inventors (file->zipper (first patent-files))))

; ELASTISCH
(def cmapping
  { "patent"
    { :properties
      { :inventors { :type "string" }}}})

(defn store-patent [patent]
  (esd/create "patalyze_development" "patent" patent))

(esr/connect! "http://127.0.0.1:9200")
;; creates an index with default settings and no custom mapping types

(esi/delete "patalyze_development")
(esd/delete-by-query-across-all-indexes-and-types (q/match-all))
(esi/create "patalyze_development" :mappings cmapping)

(map #(store-patent %) (take 100 patents))

(esd/search "patalyze_development" "patent" :query { :filter {:term {:inventors "Lowery"}}})
(esd/search "patalyze_development" "patent" :query {:term {:inventors "Daniel Francis Lowery"}})
(esd/search "patalyze_development" "patent" :query {:term {:title "plastic"}})
(first patents)
(esd/search "patalyze_development" "patent" :query (q/match-all))
