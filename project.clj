(defproject patalyze "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.match "0.2.1"]
                 [prismatic/schema "0.2.1"]
                 [com.taoensso/carmine "2.6.2"]
                 [com.taoensso/timbre "3.2.1"]
                 [riemann-clojure-client "0.2.10"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [enlive "1.1.5"]
                 [clojurewerkz/elastisch "2.0.0-beta4"]]
                 ; [lein-light-nrepl "0.0.17"]]
  :plugins      [[lein-kibit "0.0.8"]]

  ; :repl-options {:nrepl-middleware [lighttable.nrepl.handler/lighttable-ops]}

  :main patalyze.nrepl
  :uberjar-name "patalyze-standalone.jar")
