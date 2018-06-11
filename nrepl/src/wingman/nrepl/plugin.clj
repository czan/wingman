(ns wingman.nrepl.plugin)

(defn middleware [project]
  (-> project
      (update-in [:dependencies]
                 (fnil into [])
                 [['org.clojars.czan/wingman.nrepl "0.2.1-SNAPSHOT"]])
      (update-in [:repl-options :nrepl-middleware]
                 (fnil into [])
                 (do (require 'wingman.nrepl)
                     @(resolve 'wingman.nrepl/middleware)))))
