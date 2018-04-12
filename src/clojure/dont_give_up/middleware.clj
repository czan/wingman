(ns dont-give-up.middleware
  (:require [dont-give-up.core :as dgu :refer (with-handlers with-restarts)]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.misc :refer (response-for uuid)]
            [clojure.tools.nrepl.middleware.session :refer (session)]
            [clojure.tools.nrepl.middleware :refer (set-descriptor!)]
            [clojure.tools.nrepl.middleware.interruptible-eval :as e]
            [clojure.pprint :refer [pprint]]
            [cider.nrepl.middleware.stacktrace :refer [analyze-causes]]
            [cider.nrepl.middleware.pprint :refer [wrap-pprint-fn]]))

(def awaiting-restarts (atom {}))
(def awaiting-prompts (atom {}))

(defn prompt-for-restarts [ex restarts]
  (if (seq restarts)
    (let [index (promise)
          id (uuid)
          {:keys [transport session] :as msg} e/*msg*]
      (swap! awaiting-restarts assoc id index)
      (try
        (t/send transport
                (response-for msg
                              :id id
                              :type "restart/prompt"
                              :error (loop [ex ex]
                                       (if-let [cause (.getCause ex)]
                                         (recur cause)
                                         (or (.getMessage ex)
                                             (.getSimpleName (.getClass ex)))))
                              :causes (map #(response-for msg %)
                                           (analyze-causes ex pprint))
                              :detail (with-out-str
                                        (let [out (java.io.PrintWriter. *out*)]
                                          (.printStackTrace ex out)))
                              :restarts (mapv (fn [{:keys [name describe]}]
                                                [(pr-str name) (describe ex)])
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

(defn prompt-for-input [prompt]
  (let [timeout (Object.)
        input (promise)
        id (uuid)
        {:keys [transport session] :as msg} e/*msg*]
    (swap! awaiting-prompts assoc id input)
    (try
      (t/send transport
              (response-for msg
                            :id id
                            :type "restart/ask"
                            :prompt prompt))
      (loop []
        (let [value (deref input 100 timeout)]
          (cond
            (Thread/interrupted) (throw (InterruptedException.))
            (= value timeout) (recur)
            :else value)))
      (finally
        (swap! awaiting-prompts dissoc id)))))

(defmacro with-interactive-handler [& body]
  `(with-handlers [(Throwable ex#
                     (let [restart# (prompt-for-restarts ex# dgu/*restarts*)]
                       (if restart#
                         (apply dgu/use-restart restart#
                                ((:make-arguments restart#) ex#))
                         (throw ex#))))]
     ~@body))

(defn unbound-var-exception? [ex]
  (and (instance? clojure.lang.Compiler$CompilerException ex)
       (or (.startsWith (.getMessage (.getCause ex))
                        "Unable to resolve symbol: ")
           (.startsWith (.getMessage (.getCause ex))
                        "Unable to resolve var: ")
           (.startsWith (.getMessage (.getCause ex))
                        "No such var: "))))

(defn extract-var-name [ex]
  (read-string (.substring (.getMessage (.getCause ex))
                           (condp #(when (.startsWith %2 %1) %1)
                               (.getMessage (.getCause ex))
                             "Unable to resolve symbol: " :>> count
                             "Unable to resolve var: " :>> count
                             "No such var: " :>> count))))

(defn missing-classname-exception? [ex]
  (and (instance? clojure.lang.Compiler$CompilerException ex)
       (.startsWith (.getMessage (.getCause ex))
                    "Unable to resolve classname: ")))

(defn extract-classname [ex]
  (read-string (.substring (.getMessage (.getCause ex))
                           (count "Unable to resolve classname:"))))

(defn unknown-ns-exception? [ex]
  (and (instance? clojure.lang.Compiler$CompilerException ex)
       (.startsWith (.getMessage (.getCause ex))
                    "No such namespace: ")))

(defn extract-ns-name [ex]
  (read-string (.substring (.getMessage (.getCause ex))
                           (count "No such namespace: "))))

(defmacro with-retry-restart
  [msg & body]
  {:style/indent [1]}
  `(letfn [(run# []
             (with-restarts [(:retry []
                               :describe ~msg
                               (run#))
                             (:define-and-retry [sym# value#]
                               :applicable? #'unbound-var-exception?
                               :describe #(str "Provide a value for `" (pr-str (extract-var-name %)) "` and retry the evaluation.")
                               :arguments #(cons (extract-var-name %) (dgu/read-and-eval-value %))
                               (if (namespace sym#)
                                 (intern (find-ns (symbol (namespace sym#)))
                                         (symbol (name sym#))
                                         value#)
                                 (intern *ns* sym# value#))
                               (run#))
                             (:refer-and-retry [sym# ns#]
                               :applicable? #(unbound-var-exception? %)
                               :describe #(str "Provide a namespace to refer `" (pr-str (extract-var-name %)) "` from and retry the evaluation.")
                               :arguments #(cons (extract-var-name %) (dgu/read-unevaluated-value %))
                               (when-not (find-ns ns#)
                                 (require ns#))
                               (binding [*ns* (or (and (namespace sym#)
                                                       (find-ns (symbol (namespace sym#))))
                                                  *ns*)]
                                 (refer ns# :only [(symbol (name sym#))]))
                               (run#))
                             (:import-and-retry [classname# package#]
                               :applicable? #'missing-classname-exception?
                               :describe "Provide a package to import the class from and retry the evaluation."
                               :arguments #(cons (extract-classname %) (dgu/read-unevaluated-value %))
                               (.importClass *ns*
                                             (clojure.lang.RT/classForName (str (name package#) "." (name classname#))))
                               (run#))
                             (:require-and-retry [ns#]
                               :applicable? #'unknown-ns-exception?
                               :describe #(str "Require the `" (pr-str (extract-ns-name %)) "` namespace and retry the evaluation.")
                               :arguments #(list (extract-ns-name %))
                               (require ns#)
                               (run#))
                             (:require-alias-and-retry [alias# ns#]
                               :applicable? #'unknown-ns-exception?
                               :describe #(str "Provide a namespace name, alias it as `" (pr-str (extract-ns-name %)) "` and retry the evaluation.")
                               :arguments #(cons (extract-ns-name %) (dgu/read-unevaluated-value %))
                               (require [ns# :as alias#])
                               (run#))
                             (:create-and-retry [ns#]
                               :applicable? #'unknown-ns-exception?
                               :describe #(str "Create the `" (pr-str (extract-ns-name %)) "` namespace and retry the evaluation.")
                               :arguments #(list (extract-ns-name %))
                               (create-ns ns#)
                               (run#))]
               ~@body))]
     (run#)))

(defn handled-eval [form]
  (binding [dgu/*restarts* []]
    (with-interactive-handler
      (with-retry-restart "Retry the REPL evaluation."
        (clojure.core/eval form)))))

(defn handled-future-call [future-call]
  (let [future-call (or (::original (meta future-call))
                        future-call)]
    (with-meta
      (fn [f]
        (future-call (fn []
                       (binding [dgu/*restarts* []]
                         (with-interactive-handler
                           (with-retry-restart "Retry the future evaluation from the start."
                             (f)))))))
      {::original future-call})))

(alter-var-root #'clojure.core/future-call handled-future-call)

(defn handled-send-via [send-via]
  (let [send-via (or (::original (meta send-via))
                     send-via)]
    (with-meta
      (fn [executor agent f & args]
        (letfn [(run []
                  (with-restarts [(:restart-and-retry []
                                    :applicable? (fn [ex]
                                                   (.startsWith (.getMessage ex) "Agent is failed"))
                                    :describe "Restart the agent and retry this action dispatch."
                                    (restart-agent agent @agent)
                                    (run))
                                  (:restart-with-state-and-retry [state]
                                    :applicable? (fn [ex]
                                                   (.startsWith (.getMessage ex) "Agent is failed"))
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
          (run)))
      {::original send-via})))

(alter-var-root #'clojure.core/send-via handled-send-via)

(defn run-with-restart-stuff [h {:keys [op code eval] :as msg}]
  (h (if (and (= op "eval")
              (nil? eval))
       (assoc msg :eval "dont-give-up.middleware/handled-eval")
       msg)))

(defn choose-restart [{:keys [id restart transport] :as msg}]
  (let [promise (get (deref awaiting-restarts) id)]
    (if promise
      (do (deliver promise (if (number? restart) restart))
          (t/send transport (response-for msg :status :done)))
      (t/send transport (response-for msg :status :error)))))

(defn answer-prompt [{:keys [id input transport] :as msg}]
  (let [promise (get (deref awaiting-prompts) id)]
    (if promise
      (do (deliver promise input)
          (t/send transport (response-for msg :status :done)))
      (t/send transport (response-for msg :status :error)))))

(defn handle-restarts [h]
  (fn [msg]
    (case (:op msg)
      "eval" (run-with-restart-stuff h msg)
      "restart/choose" (choose-restart msg)
      "restart/answer" (answer-prompt msg)
      (h msg))))

(set-descriptor! #'handle-restarts
                 {:requires #{#'session #'wrap-pprint-fn}
                  :expects #{"eval"}
                  :handles {"restart/choose" {:doc "Select a restart"
                                              :requires {"index" "The index of the reset to choose"}
                                              :optional {}
                                              :returns {}}
                            "restart/answer" {:doc "Provide input to a restart prompt"
                                              :requires {"input" "The input provided to the restart handler"}
                                              :optional {}
                                              :returns {}}}})
