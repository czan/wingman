(ns dont-give-up.middleware
  (:require [dont-give-up.core :as dgu :refer (with-handlers with-restarts make-restart)]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.misc :refer (response-for uuid)]
            [clojure.tools.nrepl.middleware.session :refer (session)]
            [clojure.tools.nrepl.middleware :refer (set-descriptor!)]
            [clojure.tools.nrepl.middleware.interruptible-eval :as e]
            [clojure.pprint :refer [pprint]]))

(def awaiting-restarts (atom {}))
(def awaiting-prompts (atom {}))

(defn analyze-causes [ex pprint]
  (when-let [f (ns-resolve 'cider.nrepl.middleware.stacktrace 'analyze-causes)]
    (f ex pprint)))

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
                              :causes (analyze-causes ex pprint)
                              :abort (or (:description (dgu/find-restart ::abort))
                                         "Rethrow the exception.")
                              :restarts (mapv (fn [{:keys [name description]}]
                                                [(pr-str name) description])
                                              (remove #(= (:name %) ::abort)
                                                      restarts))))
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
  `(dgu/with-cleared-restarts
     (with-handlers [(Throwable ex#
                       (if-let [restart# (or (prompt-for-restarts ex# (dgu/list-restarts))
                                             (dgu/find-restart ::abort))]
                         (apply dgu/use-restart restart#
                                (binding [dgu/prompt-user prompt-for-input
                                          dgu/eval* handled-eval]
                                  ((:make-arguments restart#))))
                         (throw ex#)))]
       ~@body)))

(defn unbound-var-exception? [ex]
  (and (instance? clojure.lang.Compiler$CompilerException ex)
       (or (.startsWith (.getMessage (.getCause ex))
                        "Unable to resolve symbol: ")
           (.startsWith (.getMessage (.getCause ex))
                        "Unable to resolve var: ")
           (.startsWith (.getMessage (.getCause ex))
                        "No such var: "))))

(def unbound-var-messages ["Unable to resolve symbol: "
                           "Unable to resolve var: "
                           "No such var: "])
(defn extract-unbound-var-name [ex]
  (and (instance? clojure.lang.Compiler$CompilerException ex)
       (let [message (.getMessage (.getCause ex))]
         (some #(when (.startsWith message %)
                  (read-string (.substring message (count %))))
               unbound-var-messages))))

(def missing-class-messages ["Unable to resolve classname: "])
(defn extract-missing-class-name [ex]
  (and (instance? clojure.lang.Compiler$CompilerException ex)
       (let [message (.getMessage (.getCause ex))]
         (some #(when (.startsWith message %)
                  (read-string (.substring message (count %))))
               missing-class-messages))))

(def unknown-ns-messages ["No such namespace: "])
(defn extract-ns-name [ex]
  (and (instance? clojure.lang.Compiler$CompilerException ex)
       (let [message (.getMessage (.getCause ex))]
         (some #(when (.startsWith message %)
                  (read-string (.substring message (count %))))
               unknown-ns-messages))))

(defn make-retries [retry-msg run]
  (fn [ex]
    (concat
     [(make-restart :retry retry-msg
                    (constantly nil)
                    run)]
     (when-let [var (extract-unbound-var-name ex)]
       [(make-restart :define-and-retry
                      (str "Provide a value for " (pr-str var) " and retry the evaluation.")
                      #(dgu/read-and-eval-form ex)
                      (fn [value]
                        (if-let [ns (namespace var)]
                          (intern (find-ns (symbol ns))
                                  (symbol (name var))
                                  value)
                          (intern *ns* var value))
                        (run)))
        (make-restart :refer-and-retry
                      (str "Provide a namespace to refer " (pr-str var) " from and retry the evaluation.")
                      #(dgu/read-form ex)
                      (fn [ns]
                        (when-not (find-ns ns)
                          (require ns))
                        (binding [*ns* (or (and (namespace var)
                                                (find-ns (symbol namespace)))
                                           *ns*)]
                          (refer ns :only [(symbol (name var))]))
                        (run)))])
     (when-let [class (extract-missing-class-name ex)]
       [(make-restart :import-and-retry
                       "Provide a package to import the class from and retry the evaluation."
                       #(dgu/read-form ex)
                       (fn [package]
                         (.importClass *ns* (clojure.lang.RT/classForName (str (name package) "." (name class))))
                         (run)))])
     (when-let [ns (extract-ns-name ex)]
       [(make-restart :require-and-retry
                      (str "Require the " (pr-str ns) " namespace and retry the evaluation.")
                      (constantly nil)
                      #(do (require ns)
                           (run)))
        (make-restart :require-alias-and-retry
                      (str "Provide a namespace name, alias it as " (pr-str ns) " and retry the evaluation.")
                      #(dgu/read-form ex)
                      (fn [orig-ns]
                        (require [orig-ns :as ns])
                        (run)))
        (make-restart :create-and-retry
                      (str "Create the " (pr-str ns) " namespace and retry the evaluation.")
                      (constantly nil)
                      #(do (create-ns ns)
                           (run)))])
     [(make-restart ::abort
                    "Abort this evaluation."
                    (constantly nil)
                    #(throw (ThreadDeath.)))])))

(defmacro with-retry-restart
  [msg & body]
  {:style/indent [1]}
  `(letfn [(run# []
             (dgu/with-restarts-fn
               (fn ^:once []
                 ~@body)
               (make-retries ~msg run#)))]
     (run#)))

(defn handled-eval [form]
  (with-interactive-handler
    (with-retry-restart "Retry the REPL evaluation."
      (clojure.core/eval form))))

(defn handled-future-call [future-call]
  (let [future-call (or (::original (meta future-call))
                        future-call)]
    (with-meta
      (fn [f]
        (future-call (fn []
                       (with-interactive-handler
                         (with-retry-restart "Retry the future evaluation from the start."
                           (f))))))
      {::original future-call})))

(defn handled-agent-fn [f]
  (fn [state & args]
    (with-interactive-handler
      (with-retry-restart "Retry the agent action from the start."
        (letfn [(run [state args]
                  (with-restarts [(:ignore []
                                    :describe "Ignore this action and leave the agent's state unchanged."
                                    state)
                                  (:ignore-and-replace [state]
                                    :describe "Ignore this action and provide a new state for the agent."
                                    :arguments #'dgu/read-form
                                    state)
                                  (:replace-and-retry [state]
                                    :describe "Provide a new state for the agent, then retry the action."
                                    :arguments #'dgu/read-form
                                    (run state args))]
                    (apply f state args)))]
          (run state args))))))

(defn handled-send-via [send-via]
  (let [send-via (or (::original (meta send-via))
                     send-via)]
    (with-meta
      (fn [executor agent f & args]
        (letfn [(run []
                  (with-restarts [(:restart-and-retry []
                                    :applicable? #(.startsWith (.getMessage %) "Agent is failed")
                                    :describe "Restart the agent and retry this action dispatch."
                                    (restart-agent agent @agent)
                                    (run))
                                  (:restart-with-state-and-retry [state]
                                    :applicable? #(.startsWith (.getMessage %) "Agent is failed")
                                    :describe "Provide a new state to restart the agent and retry this action dispatch."
                                    :arguments #'dgu/read-form
                                    (restart-agent agent state)
                                    (run))]
                    (apply send-via
                           executor
                           agent
                           (handled-agent-fn f)
                           args)))]
          (run)))
      {::original send-via})))

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
  (if (and (find-ns 'cider.nrepl.middleware.pprint)
           (find-ns 'cider.nrepl.middleware.stacktrace))
    (fn [msg]
      (case (:op msg)
        "eval" (run-with-restart-stuff h msg)
        "restart/choose" (choose-restart msg)
        "restart/answer" (answer-prompt msg)
        (h msg)))
    h))

(if (and (find-ns 'cider.nrepl.middleware.pprint)
         (find-ns 'cider.nrepl.middleware.stacktrace))
  (do (require 'cider.nrepl.middleware.pprint)
      (require 'cider.nrepl.middleware.stacktrace)
      (alter-var-root #'clojure.core/future-call handled-future-call)
      (alter-var-root #'clojure.core/send-via handled-send-via)
      (set-descriptor! #'handle-restarts
                       {:requires #{#'session (ns-resolve 'cider.nrepl.middleware.pprint 'wrap-pprint-fn)}
                        :expects #{"eval"}
                        :handles {"restart/choose" {:doc "Select a restart"
                                                    :requires {"index" "The index of the reset to choose"}
                                                    :optional {}
                                                    :returns {}}
                                  "restart/answer" {:doc "Provide input to a restart prompt"
                                                    :requires {"input" "The input provided to the restart handler"}
                                                    :optional {}
                                                    :returns {}}}}))
  ;; if we can't find the namespaces, just give up!
  (set-descriptor! #'handle-restarts
                   {:requires #{}
                    :expects #{}
                    :handles {}}))
