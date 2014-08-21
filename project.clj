(defproject patalyze "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.match "0.2.1"]
                 [prismatic/schema "0.2.1"]
                 [com.taoensso/timbre "3.2.1"]
                 [riemann-clojure-client "0.2.11"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [clojurewerkz/elastisch "2.0.0"]
                 [clj-aws-s3 "0.3.9"]
                 [enlive "1.1.5"]

                 [liberator "0.12.0"]
                 [compojure "1.1.8"]
                 [ring/ring-core "1.3.0"]
                 [ring/ring-devel "1.3.0"]
                 [ring/ring-jetty-adapter "1.3.0"]
                 [environ "0.5.0"]]
                 ; [lein-light-nrepl "0.0.17"]]

  :plugins [[lein-environ "0.5.0"]
            [lein-ring "0.8.10"]]
  ; :repl-options {:nrepl-middleware [lighttable.nrepl.handler/lighttable-ops]}
  :profiles {:dev {:repl-options {:init-ns patalyze.core
                                  :init (initialize-logger!)}
                   :env {:data-dir      "data"
                         :db-private    "127.0.0.1"
                         :elasticsearch "http://127.0.0.1:9200"
                         :riemann       "127.0.0.1"}}}

  :ring {:handler patalyze.core/handler
         :adapter {:port 8000}}

  :main patalyze.core
  :uberjar-name "patalyze-standalone.jar")
