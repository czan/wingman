(ns wingman.nrepl.plugin)

(defn middleware [project]
  (-> project
      (update-in [:dependencies]
                 (fnil into [])
                 [['org.clojars.czan/wingman.nrepl "0.3.0"]])
      (update-in [:repl-options :nrepl-middleware]
                 (fnil into [])
                 (do (require 'wingman.nrepl)
                     @(resolve 'wingman.nrepl/middleware)))))
