(ns patalyze.storage
  (:require [environ.core   :refer [env]]
            [aws.sdk.s3     :as s3]
            [clojure.string :as s])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.zip GZIPInputStream GZIPOutputStream]
           [org.apache.commons.io IOUtils]))


(def bucket "patalyze")
(def cred {:access-key (env :aws-key)
           :secret-key (env :aws-secret)})

(defn str->gzipped-bytes [str]
  (with-open [out (ByteArrayOutputStream.)
              gzip (GZIPOutputStream. out)]
    (do
      (.write gzip (.getBytes str))
      (.finish gzip)
      (.toByteArray out))))

(defn gzipped-input-stream->str [input-stream encoding]
  (with-open [out (ByteArrayOutputStream.)]
    (IOUtils/copy (GZIPInputStream. input-stream) out)
    (.close input-stream)
    (.toString out encoding)))

(defn store-applications [pub-date applications]
  (let [string-val (pr-str applications)
        key        (str "applications/" (name pub-date) ".edn.gz")
        cache      (str (env :data-dir ) "/cache/applications/" (name pub-date) ".edn.gz")
        gzipped    (ByteArrayInputStream. (str->gzipped-bytes string-val))]
    (with-open [out (clojure.java.io/output-stream cache)]
      (do
        (clojure.java.io/copy gzipped out)
        (try
          (s3/put-object cred bucket key gzipped {:content-encoding "gzip"})
          (catch com.amazonaws.AmazonClientException e false))))))

(defn retrieve-applications [pub-date]
  (let [key      (str "applications/" (name pub-date) ".edn")
        cache    (str (env :data-dir ) "/cache/applications/" (name pub-date) ".edn.gz")
        get-obj  #(clojure.java.io/input-stream (:content (s3/get-object cred bucket key)))]
    (if (.exists (clojure.java.io/file cache))
     (gzipped-input-stream->str (clojure.java.io/input-stream cache) "UTF-8")
     (try
       (with-open [content (get-obj)
                   out     (clojure.java.io/output-stream cache)]
         (clojure.java.io/copy content out)
         (read-string (gzipped-input-stream->str content "UTF-8")))
       (catch com.amazonaws.AmazonClientException e false)))))

(defn list-applications []
  (map :key
       (:objects (s3/list-objects cred bucket {:prefix "applications/"}))))

(comment
  (list-applications)

  (map :key
       (:objects (s3/list-objects cred bucket)))
  (slurp (:content (s3/get-object cred bucket "multipart-stream-test")))

  (def st
    (ByteArrayInputStream.
     (.getBytes "TEST" "UTF-8")))

  (map #(apply str %) (partition-all (* 5 1024 1024) (repeat "xxx ")))
  (str \a \b)

  (repeat 1000 "xxx ")
  (def is (->> (repeat 10000 "xxx ")
               (map #(java.io.ByteArrayInputStream. (.getBytes %)))
               (clojure.lang.SeqEnumeration.)
               (java.io.SequenceInputStream.)))

  (s3/put-multipart-stream cred bucket "multipart-stream-test" is)
  (-> is (clojure.java.io/reader) ((fn [r] (apply str (map char (repeatedly 20 #(.read r)))))))
  (slurp is)
  (-> *e ex-data :object :body slurp)
  (s3/delete-object cred bucket "applications2004.edn"))
