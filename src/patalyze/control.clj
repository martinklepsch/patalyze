(ns patalyze.control
  (:require [patalyze.retrieval :refer [status where get-archive]]
            [patalyze.parser    :refer [parse]]
            [patalyze.storage   :refer [cache upload-cached download load-cached]]
            [environ.core       :refer [env]]
            [taoensso.timbre         :as timbre :refer (log  trace  debug  info  warn  error)]
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

  (defn o [n] (take n (where (status) {:on-s3 true :cached true})))

  (map download (keys (o 100)))

  (count (o 1000))

  (keys (o 2))

  (upload-cached "20131121_wk47")
  (first (vals (parse "20010322_wk12")))

  (map get-archive (take 3 (where (status) {:on-s3 false :on-disk false})))
  (pmap get-archive (where (status) {:on-s3 true} "2001"))

  (keys (where (status) {:on-s3 true} "200103"))

  (count (where (status) {:on-s3 true :cached false} "2013"))

  (count (keys (where (status) {:on-disk true})))

  (let [processors (cp/ncpus)
        chunks     (+ 2 processors)
        to-parse   (take 2 (keys (where (status) {:on-disk true})))
        n-to-parse (count to-parse)
        chunk-size (if (zero? (quot n-to-parse chunks)) 1 (quot n-to-parse chunks))
        rest-chunk (if (< (* chunks chunk-size) n-to-parse) 0 (mod n-to-parse chunk-size))
        cache-fn   #(do
                      (cache %2)
                      (info "Cache Status:" (count (file-seq (clojure.java.io/file (env :data-dir) "cache/applications"))) "/" n-to-parse))]

    (info "Starting Parsing Process")
    (info (count to-parse) "archives to parse")
    (info (float (/ n-to-parse chunk-size)) "chunks")
    (info chunk-size "+" rest-chunk "archives per chunk")
    (cp/pmap chunks (fn [al] (doall (map-indexed cache-fn (map #(parse %) al))))
          (partition-all chunk-size to-parse)))

  (first (partition-all 3   (keys (where (status) {:on-disk true} "201"))))

  (map parse-to-s3 (take 1 (keys (where (status) {:on-s3 false :on-disk true}))))

  (load-into-atom db (where (status) {:cached true} "2013"))

  (reset! db {:loaded-archives #{} :patents #{}})

  (count (where (status) {:cached true}))

  (/ 347148 12)

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

  (count @db)

  (where (status) {:on-s3 true} "20021212"))
