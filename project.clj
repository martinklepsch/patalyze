(defproject patalyze "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/core.match "0.2.1"]
                 [prismatic/schema "0.2.1"]
                 [enlive "1.1.5"]
                 [clojurewerkz/elastisch "2.0.0-beta4"]]
  :plugins      [[lein-kibit "0.0.8"]])
