(ns patalyze.nrepl
  (:require [clojure.tools.nrepl.server :refer (start-server stop-server default-handler)])
  (:gen-class :main true))

(defn -main
  "The application's main function"
  [& args]
  (start-server :bind "127.0.0.1" :port 42042 :handler (default-handler [lighttable.nrepl.handler/lighttable-ops])))
