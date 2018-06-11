(ns wingman.nrepl.plugin)

(defn middleware [project]
  (-> project
      (update-in [:dependencies]
                 (fnil into [])
                 [['wingman/wingman.nrepl "0.3.1"]])
      (update-in [:repl-options :nrepl-middleware]
                 (fnil into [])
                 (do (require 'wingman.nrepl)
                     @(resolve 'wingman.nrepl/middleware)))))
