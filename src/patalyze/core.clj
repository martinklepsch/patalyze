(ns patalyze.core
  (:require [clojure.tools.nrepl.server :as nrepl]
            [patalyze.index             :as index]
            [environ.core               :refer  [env]]
            [liberator.core             :refer  [resource defresource]]
            [ring.middleware.params     :refer  [wrap-params]]
            [ring.middleware.reload     :refer  [wrap-reload]]
            [ring.adapter.jetty         :refer  [run-jetty]]
            [compojure.core             :refer  [defroutes ANY]]
            [taoensso.timbre            :as timbre :refer [log  trace  debug  info  warn  error]])
  (:gen-class))

(defn initialize-logger! []
  (timbre/set-config! [:appenders :spit :enabled?] true)
  (timbre/set-config! [:shared-appender-config :spit-filename]
                      (str (env :data-dir) "/patalyze.log")))

(defn simplify-maps [ms]
  (map
    (fn [m]
      (update-in m [:inventors] #(clojure.string/join ", " %)))
    ms))

(defresource orgs [org]
  :available-media-types  ["text/csv" "application/json"]
  :handle-ok  (fn  [_] (simplify-maps (index/patents-by-org org))))

(defresource inventors [inventor]
  :available-media-types  ["text/csv" "application/json"]
  :handle-ok  (fn  [_] (simplify-maps (index/patents-by-inventor inventor))))

(defresource stats []
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (index/patent-count)))

(defroutes app
  (ANY "/by-org/:org" [org] (orgs org))
  (ANY "/by-inventor/:inventor" [inventor] (inventors inventor))
  (ANY "/stats" [] (stats)))

(def handler
  (-> app
      (wrap-reload)
      (wrap-params)))

(defn ensure-dirs-exist [& directories]
  (doseq [p directories]
    (if-not (.exists (clojure.java.io/as-file p))
      (.mkdir (java.io.File. p)))))

(defn -main [& _]
  "The application's main function"
  (initialize-logger!)
  (ensure-dirs-exist (map #(str (env :data-dir) %) "/applications" "/cache"))
  (nrepl/start-server :bind "0.0.0.0" :port 42042)
  (run-jetty #'handler {:port 3000 :join? false})
  (info "nREPL Server started on port 42042")) ; :handler (default-handler lighttable-ops)))
