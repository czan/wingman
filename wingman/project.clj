(defproject wingman "0.3.1-SNAPSHOT"
  :description "Restartable exception handling for Clojure"
  :url "https://github.com/czan/wingman"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [wingman/base "0.3.1-SNAPSHOT"]]
  :profiles {:dev {:plugins [[lein-doo "0.1.10"]]
                   :doo {:build "test"
                         :alias {:default [:node]}}
                   :cljsbuild {:builds [{:id "test"
                                         :source-paths ["src" "test"]
                                         :compiler {:output-to "testing.js"
                                                    :main wingman.core-test
                                                    :optimizations :none
                                                    :target :nodejs}}]}}}
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]])
