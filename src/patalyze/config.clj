(ns patalyze.config
  (:require [environ.core :refer [env]]
            [clojurewerkz.elastisch.rest :as esr]
            [riemann.client       :as r]))

(def c  (delay (r/tcp-client {:host (env :db-private)})))
(def es (delay (esr/connect (str "http://" (env :db-private) ":9200"))))
