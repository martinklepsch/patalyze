(ns patalyze.control
  (:require [patalyze.retrieval :refer [status where]]
            [patalyze.parser    :refer [parse-to-s3]]
            [patalyze.storage   :refer [retrieve-applications]]))

(set! *print-length* 50)
(set! *print-level* nil)

(def db
  (atom {:loaded-archives #{} :patents #{}}))

(defn load-into-atom [a status-entries]
  (let [before (count (:patents @a))
        to-add (clojure.set/difference (set (keys status-entries)) (:loaded-archives @a))]
    (doseq [k to-add]
      (swap! a (fn [old archive-identifier archive-patents]
                 {:loaded-archives (clojure.set/union (:loaded-archives old)
                                                      archive-identifier)
                  :patents         (clojure.set/union (:patents old)
                                                      archive-patents)})
             (set [k])
             (set (retrieve-applications k)))
      (println k))
    {:archives-loaded (count (:loaded-archives @a))
     :new-archives-added to-add
     :before before
     :after (count (:patents @a))
     :diff (- (count (:patents @a)) before)}))

(defn dups [seq]
  (for [[id freq] (frequencies seq)  ;; get the frequencies, destructure
        :when (> freq 1)]            ;; this is the filter condition
   id))

(comment
  (defn o [n] (take n (where (status) {:cached true})))
  (load-into-atom db (o 1100))

  (fetch (take 3 (where (status) {:on-s3 false :on-disk false})))
  (keys (where (status) {:on-s3 false :on-disk true}))

  (map parse-to-s3 (take 1 (keys (where (status) {:on-s3 false :on-disk true}))))

  (load-into-db (where (status) {:on-s3 true}))

  (second
          (reduce (fn [[found dups] e]
                    (if (contains? found e)
                      [found (conj dups e)]
                      [(conj found e) dups]))
                  [#{} #{}]
                  [1 1 2 3 4 5 6 6 7]))

  (second
          (reduce (fn [[found dups] e]
                    (if (contains? found e)
                      [found (conj dups e)]
                      [(conj found e) dups]))
                  [#{} #{}]
                  (flatten
                   (map #(retrieve-applications %)
                        (keys (where (status) {:on-s3 true}))))))

  (dups
   (flatten
    (map #(retrieve-applications %)
         (keys (where (status) {:on-s3 true})))))

  (reset! db #{})
  (count @db)

  (where (status) {:on-s3 true} "20021212"))
