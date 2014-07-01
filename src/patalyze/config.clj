(ns patalyze.config
  (:require [environ.core :as environ]
            [clojurewerkz.elastisch.rest :as esr]
            [riemann.client       :as r]))

(defn env [k]
  (delay (environ/env k)))
; (deref (env :data-dir))

(def c  (delay (r/tcp-client {:host (deref (env :db-private))})))
(def es (delay (esr/connect (str "http://" (deref (env :db-private)) ":9200"))))
