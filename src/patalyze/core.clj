(ns patalyze.nrepl
  (:require [clojure.tools.nrepl.server :refer (start-server stop-server default-handler)])
            ; [lighttable.nrepl.handler :refer (lighttable-ops)])
  (:gen-class :main true))

(defn -main
  "The application's main function"
  [& _]
  (timbre/set-config! [:appenders :spit :enabled?] true)
  (timbre/set-config! [:shared-appender-config :spit-filename] (str (env :data-dir) "/patalyze.log"))

  (start-server :bind "0.0.0.0" :port 42042)
  (println "nREPL Server started on port 42042")); :handler (default-handler lighttable-ops)))
