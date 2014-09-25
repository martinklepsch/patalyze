(ns patalyze.control
  (:require [patalyze.retrieval :refer [status where get-archive]]
            [patalyze.parser    :refer [parse]]
            [patalyze.storage   :refer [cache upload-cached download load-cached]]
            [com.climate.claypoole :as cp]))

(set! *print-length* 100)
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
             (set (load-cached k)))
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
  (take 10 (load-cached (first (keys  (where (status) {:cached true})))))

  (defn o [n] (take n (where (status) {:cached true} "2013")))

  (map get-archive (o 2))

  (keys (o 2))

  (upload-cached "20131121_wk47")
  (first (vals (parse "20010322_wk12")))

  (map get-archive (take 3 (where (status) {:on-s3 false :on-disk false})))
  (pmap get-archive (where (status) {:on-s3 true} "2001"))

  (keys (where (status) {:on-s3 true} "200103"))

  (count (where (status) {:on-s3 true :cached false} "2013"))

  (map parse-to-s3 (take 5 (keys (where (status) {:on-s3 false :on-disk true}))))

  (let [chunks     4
        to-parse   (keys (where (status) {:on-disk true} "2001"))
        chunk-size (/ (count to-parse) chunks)]
    (cp/pmap chunks #(map cache (doall (map parse %))) (partition-all chunk-size to-parse)))

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
