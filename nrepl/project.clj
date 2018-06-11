(defproject org.clojars.czan/wingman.nrepl "0.2.1-SNAPSHOT"
  :description "Restartable exception handling for Clojure"
  :url "https://github.com/czan/wingman"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojars.czan/wingman.base "0.2.1-SNAPSHOT"]
                 [org.clojars.czan/wingman.sugar "0.2.1-SNAPSHOT"]]
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]])
