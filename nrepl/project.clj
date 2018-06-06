(defproject org.clojars.czan/dont-give-up.nrepl "0.2.1-SNAPSHOT"
  :description "Restartable exception handling for Clojure"
  :url "https://github.com/czan/dont-give-up.nrepl"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [org.clojars.czan/dont-give-up "0.2.0"]]
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]])
