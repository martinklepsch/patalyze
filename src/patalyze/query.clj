(ns patalyze.query
  (:require [patalyze.control :refer [db]]
            [patalyze.parser  :refer [tokenize-string]]))

(count (:patents @db))

(defn sort-map-by-value-count [m]
  "BEWARE somthing with this function is broken in that it
   breaks map lookups on the returned map"
  (into (sorted-map-by (fn [k1 k2] (<= (count (get m k2))
                                       (count (get m k1))))) m))

(defn by-company [company-name]
  (let [tn (tokenize-string company-name)]
    (filter #(clojure.set/subset? tn (:organization-tokens %)))))

(defn by-inventors [inventors]
  (filter #(not-empty (clojure.set/intersection (:inventors %) (set inventors)))))

(defn extract-inventor-filing-dates [patent]
  (into {}
        (map #(hash-map % (list (hash-map (:filing-date patent) (:organization patent))))
             (:inventors patent))))

(defn filing-dates-per-inventor [patents]
  (apply merge-with concat
         (map (fn [p]
                (extract-inventor-filing-dates p))
              patents)))

(defn nearest-from-list [ds d]
  (let [sorted (sort (conj ds d))
        idx    (.indexOf sorted d)]
    [(nth sorted (dec idx) :none-before) (nth sorted (inc idx) :none-after)]))

(defn companies-for-inventor [filing-dates date inventor]
  (let [dates-w-co (apply merge (get filing-dates inventor))
        ;; dates      (keys dates-w-co)
        ;; nearest    (nearest-from-list dates date)
        employers  (remove empty? (vals dates-w-co))]
    (cond (empty? employers) nil
          (apply = employers) (first employers)
          :else employers)))

(defn cfi [i]
  (companies-for-inventor (filing-dates-per-inventor (:patents @db))
                          nil
                          i))

(cfi "Duncan Kerr")
(cfi "Stephen P. Zadesky")
(cfi "Christopher D. Prest")

(defn companies-for-patent [filing-dates patent]
  (into {}
        (map #(hash-map % (companies-for-inventor filing-dates (:filing-date patent) %))
             (:inventors patent))))

(map :organization (sequence (by-inventors ["Stephen P. Zadesky"]) (:patents @db)))
(get (filing-dates-per-inventor (:patents @db)) "Stephen P. Zadesky")
(map (partial companies-for-patent (filing-dates-per-inventor (:patents @db)))
     nn)

(defn extended-portfolio [ps company-name]
  (let [filed-under-co      (sequence (by-company company-name) ps)
        co-inventors        (apply clojure.set/union (map :inventors filed-under-co))
        all-by-co-inventors (sequence (by-inventors co-inventors) ps)
        inventors-peers     (apply clojure.set/union (map :inventors all-by-co-inventors))
        all-by-ext-circle   (sequence (by-inventors inventors-peers) ps)
        inventors-dates     (filing-dates-per-inventor all-by-ext-circle)
        not-by-co           (clojure.set/difference (set all-by-co-inventors) (set filed-under-co))]
    {:filed-under-co (count filed-under-co)
     :all-by-inventors (count all-by-co-inventors)
     :not-by-co not-by-co}))

(map :organization (:not-by-co (extended-portfolio (:patents @db) "apple inc")))
(def nn
  (:not-by-co (extended-portfolio (:patents @db) "apple inc")))

;; Apple Inc => {:filed-under-co #{...}
;;               :all-inventors-co-employees #{...}
;;               :some-inventors-co-employees #{...}
;;               :no-inventor-co-employee #{...}}

(first (filing-dates-per-inventor (sequence (by-company "apple inc") (:patents @db))))

(count (sequence (by-inventors ["Craig A. Marciniak"]) (:patents @db)))
(count
 (sequence (patents-by-inventors
            (keys
             (filing-dates-per-inventor (sequence (by-company "apple inc") (:patents @db))))))
           (:patents @db))

(clojure.set/subset?
 (tokenize-string "Apple, Inc.")
 (tokenize-string "Apple Computer, Inc."))

(keys
 (filing-dates-per-inventor (patents-by-company (:patents @db) "apple inc")))

(patents-by-company (:patents @db) "apple inc")
(sort-by (fn [[k v]] (count v))
         (filing-dates-per-inventor (patents-by-company (:patents @db) "apple inc")))
(filing-dates-per-inventor (take 10 (:patents @db)))
(filter (fn [m] (> 1 (count (val m))))
        (filing-dates-per-inventor (patents-by-company (:patents @db) "apple inc")))
(take 10
 (filing-dates-per-inventor (patents-by-company (:patents @db) "apple inc")))
