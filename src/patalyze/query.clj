(ns patalyze.query
  (:require [patalyze.control :refer [db]]
            [patalyze.parser  :refer [tokenize-string]]
            [com.gfredericks.compare :as c]))


(defn stats
  ([patents]
     (stats patents :per-archive))
  ([patents interval]
     (let [l        (interval {:per-archive 8 :per-month 6 :per-year 4})
           archives (group-by #(apply str (take l (:publication-date %))) patents)]
       (into (sorted-map)
             (for [a archives]
               {(key a) {:patents   (count (val a))
                         :inventors (count (set (flatten (map :inventors (val a)))))}})))))

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
        (map #(hash-map % (list (hash-map (:filing-date patent) (:organization-tokens patent))))
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

(defn check-for-matches [d ds]
  (let [checked  (partition-by first (mapv #(vec [(first %) (= (second %) (second d))]) ds))
        segments (map-indexed (fn [idx date] (vector idx date)) checked)]
    {(first d) {:company (second d)
                :matches (into []
                               (for [segment segments
                                     n       (last segment)]
                                 (conj n (first segment))))}}))

(defn sum-matches [match-data]
  (let [in (group-by :company (vals match-data))]
    (map (fn [c] [(count (reduce concat (map #(filter second (:matches %)) (val c)))) (key c)])
         in)))

(defn companies-for-inventor [filing-dates date]
  (let [dates-w-co       (into (sorted-map) filing-dates)
        [before after]   (split-with #(c/< % date) (keys dates-w-co))
        filings-before   (select-keys dates-w-co before)
        filings-after    (select-keys dates-w-co after)]
    (sum-matches
     (into {}
           (for [bd (vec filings-before)]
               (check-for-matches bd (vec (reverse filings-after))))))))

(defn cfi [i]
  (let [fds (get (filing-dates-per-inventor (:patents @db)) i)]
    (into (sorted-map)
      (for [fd fds] fd))))


(comment
(split-with #(c/< % date)
            (keys (into (sorted-map) sample)))

sample
(companies-for-inventor sample "20120911")
(check-for-matches ff fl)
(filter second [[:a true 2] [:b true 4] [:c false 4]])

((fn [d ds]
   (let [checked  (partition-by first (mapv #(vec [(first %) (= (second %) (second d))]) ds))
         segments (map-indexed (fn [idx date] (vector idx date)) checked)]
     (into []
           (for [segment segments
                 n       (last segment)]
             (conj n (first segment))))))
 ["20100311" 1] [["20100710" 2] ["20111110" 3] ["20111110" 1] ["20130314" 1] ["20140301" 1]])


(def ss (map-indexed (fn [idx date] (vector idx date))
             (partition-by identity [:a :b :c :c :e :f])))
(into []
      (for [segment (map-indexed (fn [idx date] (vector idx date))
                                 (partition-by first (mapv #(vec [(first %) (= (second %) (second d))]) fl)))
            n       (last segment)]
        [n (first segment)]))
ss

[[:a 1] [:b 2] [:c 3] [:c 3] [:e 4] [:f 5]]

{"20130122" {:company c :matches [["20130207" 3] ["20140502" 10]]}}

  ; Date being checked "20130129"
  (into (sorted-map) (for [fd [{:a 3} {:b 6} {:g 20}]] fd))
  (into (sorted-map) [{:a 3} {:b 6} {:g 20}])
  {"20130122" {:company c :matches [["20130207" 3] ["20140502" 10]]}}

  (cfi "Jony Ive")
  (cfi "Elon Musk")
  (cfi "Stephen P. Zadesky" "20120101")
  (def sample
    (cfi "Christopher D. Prest"))

  (companies-for-inventor (filing-dates-per-inventor (:patents @db))
                          nil
                          "Christopher D. Prest")

  (map :organization (sequence (by-inventors ["Stephen P. Zadesky"]) (:patents @db)))
  (get (filing-dates-per-inventor (:patents @db)) "Stephen P. Zadesky")
  (map (partial companies-for-patent (filing-dates-per-inventor (:patents @db)))
       nn)

  (map :organization (:not-by-co (extended-portfolio (:patents @db) "apple inc")))
  (def nn
    (:not-by-co (extended-portfolio (:patents @db) "apple inc")))


  "Rough idea to identify company a person is working for:
   build pairs of organizations in their patents;
   split dates-w-co at date, take last of of the list before
   given date find first matching company in list after date;
   save both dates/Co's including a distance between them")

(defn companies-for-patent [filing-dates patent]
  (into {}
        (map #(hash-map % (companies-for-inventor filing-dates (:filing-date patent) %))
             (:inventors patent))))

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

(comment
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
     (filing-dates-per-inventor (patents-by-company (:patents @db) "apple inc"))))
