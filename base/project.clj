(defproject wingman/base "0.2.1-SNAPSHOT"
  :description "Restartable exception handling for Clojure"
  :url "https://github.com/czan/wingman"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :source-paths ["src"]
  :java-source-paths ["src"]
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]])
