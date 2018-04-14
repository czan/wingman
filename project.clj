(defproject org.clojars.czan/dont-give-up "0.1.1-SNAPSHOT"
  :description "Common Lisp style restarts in Clojure"
  :url "https://github.com/czan/dont-give-up"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]])
