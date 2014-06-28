(ns patalyze.core
  (:require [clojure.tools.nrepl.server :as nrepl]
            [liberator.core             :refer  [resource defresource]]
            [ring.middleware.params     :refer  [wrap-params]]
            [ring.middleware.reload     :refer  [wrap-reload]]
            [ring.adapter.jetty         :refer  [run-jetty]]
            [compojure.core             :refer  [defroutes ANY]]
            [environ.core               :refer  [env]]
            [taoensso.timbre            :as timbre :refer [log  trace  debug  info  warn  error]])
  (:gen-class))

(defroutes app
  (ANY "/test"  []  (resource)))

(def handler 
  (-> app 
      (wrap-params))) 

(defn -main
  "The application's main function"
  [& _]
  (timbre/set-config! [:appenders :spit :enabled?] true)
  (timbre/set-config! [:shared-appender-config :spit-filename] (str (env :data-dir) "/patalyze.log"))

  (nrepl/start-server :bind "0.0.0.0" :port 42042)
  (run-jetty (wrap-reload  #'handler)  {:port 3000 :join? false})
  (println "nREPL Server started on port 42042")) ; :handler (default-handler lighttable-ops)))
