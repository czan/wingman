(ns dont-give-up.middleware
  (:require [dont-give-up.core :as dgu :refer (with-handlers with-restarts)]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.misc :refer (response-for uuid)]
            [clojure.tools.nrepl.middleware.session :refer (session)]
            [clojure.tools.nrepl.middleware :refer (set-descriptor!)]
            [clojure.tools.nrepl.middleware.interruptible-eval :as e]))

(def awaiting-restarts (atom {}))

(defn prompt-for-restarts [ex args restarts]
  (if (seq restarts)
    (let [index (promise)
          id (uuid)
          {:keys [transport session] :as msg} e/*msg*]
      (swap! awaiting-restarts assoc id index)
      (try
        (t/send transport
                (response-for msg
                              :id id
                              :error (str (-> ex .getClass .getSimpleName)
                                          ": "
                                          (.getMessage ex))
                              :detail (with-out-str
                                        (let [out (java.io.PrintWriter. *out*)]
                                          (.printStackTrace ex out)))
                              :restarts (mapv (fn [{:keys [name describe]}]
                                                [name (apply describe ex args)])
                                              restarts)))
        (loop []
          (let [idx (deref index 100 :timeout)]
            (cond
              (Thread/interrupted) (throw (InterruptedException.))
              (= idx :timeout) (recur)
              :else (get restarts idx))))
        (finally
          (swap! awaiting-restarts dissoc id))))
    nil))

(defmacro with-interactive-handler [& body]
  `(with-handlers [(Throwable [ex# & args#]
                     (let [restart# (prompt-for-restarts ex# args# dgu/*restarts*)]
                       (if restart#
                         (apply dgu/use-restart restart#
                                (apply (:make-arguments restart#)
                                       ex# args#))
                         (throw ex#))))]
     ~@body))

(defmacro with-retry-restart [msg & body]
  {:style/indent [1]}
  `(letfn [(run# []
             (with-restarts [(:retry []
                               :describe ~msg
                               (run#))]
               ~@body))]
     (run#)))

(defn handled-eval [form]
  (binding [dgu/*restarts* []]
    (with-interactive-handler
      (with-retry-restart "Retry the REPL evaluation."
        (clojure.core/eval form)))))

(defn handled-future-call [future-call]
  (fn [f]
    (future-call (fn []
                   (binding [dgu/*restarts* []]
                     (with-interactive-handler
                       (with-retry-restart "Retry the future evaluation from the start."
                         (f))))))))

(alter-var-root #'clojure.core/future-call handled-future-call)

(defn handled-send-via [send-via]
  (fn [executor agent f & args]
    (letfn [(run []
              (with-restarts [(:restart-and-retry []
                                :describe "Restart the agent and retry this action dispatch."
                                (restart-agent agent @agent)
                                (run))
                              (:restart-with-state-and-retry [state]
                                :describe "Provide a new state to restart the agent and retry this action dispatch."
                                :arguments #'dgu/read-unevaluated-value
                                (restart-agent agent state)
                                (run))]
                (apply send-via
                       executor
                       agent
                       (fn [state & args]
                         (binding [dgu/*restarts* []]
                           (with-interactive-handler
                             (with-retry-restart "Retry the agent action from the start."
                               (with-restarts [(:ignore []
                                                 :describe "Ignore this action and leave the agent's state unchanged."
                                                 state)
                                               (:ignore-and-replace [state]
                                                 :describe "Ignore this action and provide a new state for the agent."
                                                 :arguments #'dgu/read-unevaluated-value
                                                 state)]
                                 (apply f state args))))))
                       args)))]
      (run))))

(alter-var-root #'clojure.core/send-via handled-send-via)

(defn run-with-restart-stuff [h {:keys [op code eval] :as msg}]
  (h (if (and (= op "eval")
              (nil? eval))
       (assoc msg :eval "dont-give-up.middleware/handled-eval")
       msg)))

(defn choose-restart [{:keys [id restart transport] :as msg}]
  (let [promise (get (deref awaiting-restarts) id)]
    (if promise
      (do (deliver promise restart)
          (t/send transport (response-for msg :status :done)))
      (t/send transport (response-for msg :status :error)))))

(defn handle-restarts [h]
  (fn [msg]
    (case (:op msg)
      "eval" (run-with-restart-stuff h msg)
      "choose-restart" (choose-restart msg)
      (h msg))))

(set-descriptor! #'handle-restarts
                 {:requires #{#'session}
                  :expects #{"eval"}
                  :handles {"choose-restart" {:doc "Select a restart"
                                              :requires {"index" "The index of the reset to choose"}
                                              :optional {}
                                              :returns {}}}})
