(defproject wingman "0.3.1-SNAPSHOT"
  :description "Restartable exception handling for Clojure"
  :url "https://github.com/czan/wingman"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [wingman/base "0.3.0"]]
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]])
