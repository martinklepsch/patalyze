(ns patalyze.nrepl
  (:require [clojure.tools.nrepl.server :refer (start-server stop-server default-handler)])
            ; [lighttable.nrepl.handler :refer (lighttable-ops)])
  (:gen-class :main true))

(defn -main
  "The application's main function"
  [& _]
  (start-server :bind "127.0.0.1" :port 42042)); :handler (default-handler lighttable-ops)))
