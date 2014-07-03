(ns patalyze.parser
  (:require [net.cgrand.enlive-html       :as html]
            [riemann.client               :as r]
            [taoensso.timbre              :as timbre]
            [clojure.core.match   :refer (match)]))

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
(defn filing-date [version xml-resource]
  (let [path (dispatch-version-path version {:v15 [:subdoc-bibliographic-information :domestic-filing-data :filing-date]
                                             :v40 [:us-bibliographic-data-application :application-reference :date]})
        title-tag (first (html/select xml-resource path))]
    (html/text title-tag)))

(defn publication-date [version xml-resource]
  (let [path (dispatch-version-path version {:v15 [:subdoc-bibliographic-information :document-id :document-date]
                                             :v40 [:us-bibliographic-data-application :publication-reference :document-id :date]})
        title-tag (first (html/select xml-resource path))]
    (html/text title-tag)))

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

; INVENTORS
(defn parse-inventor [version inventor-node]
  (let [paths   (dispatch-version-path version {:v15 [[:name :given-name] [:name :middle-name] [:name :family-name]]
                                                :v40 [[:addressbook :first-name] [:addressbook :last-name]]})
        nodes   (map #(first (html/select inventor-node %)) paths)
        fields  (remove clojure.string/blank? (html/texts nodes))]
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
                                                :v16 [:subdoc-bibliographic-information :assignee :organization-name]
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
  (let [version      (detect-version xml-str)
        xml-resource (parse xml-str)]
    {:filing-date      (filing-date version xml-resource)
     :publication-date (publication-date version xml-resource)
     :organization     (orgname version xml-resource)
     :inventors        (inventors version xml-resource)
     :abstract         (invention-abstract version xml-resource)
     :title            (invention-title version xml-resource)
     :uid              (publication-identifier version xml-resource)}))
