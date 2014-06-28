(ns patalyze.core
  (:require [clojure.tools.nrepl.server :as nrepl]
            [environ.core         :refer [env]]
            [taoensso.timbre      :as timbre :refer (log  trace  debug  info  warn  error)])
            ; [lighttable.nrepl.handler :refer (lighttable-ops)])
  (:gen-class :main true))

(defn -main
  "The application's main function"
  [& _]
  (timbre/set-config! [:appenders :spit :enabled?] true)
  (timbre/set-config! [:shared-appender-config :spit-filename] (str (env :data-dir) "/patalyze.log"))

  (nrepl/start-server :bind "0.0.0.0" :port 42042)
  (info "nREPL Server started on port 42042")); :handler (default-handler lighttable-ops)))
