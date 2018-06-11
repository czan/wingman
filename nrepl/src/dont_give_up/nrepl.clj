(ns dont-give-up.nrepl
  (:require [dont-give-up.sugar :as dgu :refer [with-handlers with-restarts without-handling]]
            [dont-give-up.core :refer [make-restart call-with-restarts prompt-user]]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.misc :refer (response-for uuid)]
            [clojure.tools.nrepl.middleware.session :refer (session)]
            [clojure.tools.nrepl.middleware :refer (set-descriptor!)]
            [clojure.tools.nrepl.middleware.interruptible-eval :as e]
            [clojure.pprint :refer [pprint]])
  (:import java.util.concurrent.Executor))

(def awaiting-restarts (atom {}))
(def awaiting-prompts (atom {}))

(defn analyze-causes [ex pprint]
  (when-let [f (ns-resolve 'cider.nrepl.middleware.stacktrace 'analyze-causes)]
    (f ex pprint)))

(def unhandled (make-restart :unhandled
                             "Leave the exception unhandled, and propagate it from the throw site"
                             (constantly nil)
                             #(assert false "This should never run")))

(def abort (make-restart :abort
                         "Abort this evaluation."
                         (constantly nil)
                         #(assert false "This should never run")))

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
                              :restarts (mapv (fn [{:keys [name description]}]
                                                [(pr-str name) description])
                                              restarts)))
        (loop []
          (let [idx (deref index 100 :timeout)]
            (cond
              (Thread/interrupted) (throw (InterruptedException.))
              (= idx :timeout) (recur)
              :else (or (get restarts idx)
                        (if (= idx "unhandled") unhandled)
                        (if (= idx "abort") abort)))))
        (finally
          (swap! awaiting-restarts dissoc id))))
    nil))

(defn prompt-for-input
  ([prompt]
   (prompt-for-input prompt nil))
  ([prompt type & args]
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
                             :prompt prompt
                             :options {:type type
                                       :args args}))
       (loop []
         (let [value (deref input 100 timeout)]
           (cond
             (Thread/interrupted) (throw (InterruptedException.))
             (= value timeout) (recur)
             :else (value))))
       (finally
         (swap! awaiting-prompts dissoc id))))))

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

(def non-dynamic-var-messages ["Can't dynamically bind non-dynamic var: "])
(defn extract-non-dynamic-var-name [ex]
  (and (instance? IllegalStateException ex)
       (let [message (.getMessage ex)]
         (some #(when (.startsWith message %)
                  (read-string (.substring message (count %))))
               non-dynamic-var-messages))))

(defn namespaces-with-var [sym]
  (for [namespace (all-ns)
        :let [v (ns-resolve namespace sym)]
        :when (-> v meta :ns (= namespace))]
    namespace))

(defn make-restarts [run retry-msg]
  (fn [ex]
    (concat
     (when-let [var (extract-unbound-var-name ex)]
       (concat
        [(make-restart :define
                       (str "Provide a value for " (pr-str var) " and retry the evaluation.")
                       #(dgu/read-and-eval-form ex)
                       (fn [value]
                         (if-let [ns (namespace var)]
                           (intern (find-ns (symbol ns))
                                   (symbol (name var))
                                   value)
                           (intern *ns* var value))
                         (run)))]
        (if-let [alias (namespace var)]
          [(make-restart :refer
                         (str "Provide a namespace to refer as " (str alias) " and retry the evaluation.")
                         #(list (read-string (prompt-user "Provide a namespace name: "
                                                          :options
                                                          (map (comp str ns-name)
                                                               (namespaces-with-var (symbol (name var)))))))
                         (fn [ns]
                           (require [ns :as (symbol alias)])
                           (run)))]
          [(make-restart :refer
                         (str "Provide a namespace to refer " (str var) " from and retry the evaluation.")
                         #(list (read-string (prompt-user "Provide a namespace name: "
                                                          :options
                                                          (map (comp str ns-name)
                                                               (namespaces-with-var var)))))
                         (fn [ns]
                           (require [ns :refer [var]])
                           (run)))])))
     (when-let [class (extract-missing-class-name ex)]
       [(make-restart :import
                      "Provide a package to import the class from and retry the evaluation."
                      #(dgu/read-form ex)
                      (fn [package]
                        (.importClass *ns* (clojure.lang.RT/classForName (str (name package) "." (name class))))
                        (run)))])
     (when-let [ns (extract-ns-name ex)]
       [(make-restart :require
                      (str "Require the " (pr-str ns) " namespace and retry the evaluation.")
                      (constantly nil)
                      #(do (require ns)
                           (run)))
        (make-restart :require-alias
                      (str "Provide a namespace name, alias it as " (pr-str ns) " and retry the evaluation.")
                      #(dgu/read-form ex)
                      (fn [orig-ns]
                        (require [orig-ns :as ns])
                        (run)))
        (make-restart :create
                      (str "Create the " (pr-str ns) " namespace and retry the evaluation.")
                      (constantly nil)
                      #(do (create-ns ns)
                           (run)))])
     [(make-restart :retry
                    retry-msg
                    (constantly nil)
                    run)])))

(defmacro with-recursive-body [name bindings & body]
  `(letfn [(~name ~(mapv first (partition 2 bindings))
            ~@body)]
     (~name ~@(map second (partition 2 bindings)))))

(defn- prompt [ex]
  (if-let [restart (prompt-for-restarts ex (dgu/list-restarts))]
    (cond
      (= restart unhandled) (dgu/unhandle-exception ex)
      (= restart abort) (throw (ThreadDeath.))
      :else (try
              (apply dgu/invoke-restart restart
                     (binding [prompt-user prompt-for-input]
                       ((:make-arguments restart))))
              (catch Exception _
                (prompt ex))))
    (throw ex)))

(defn call-with-interactive-handler [body-fn]
  (dgu/without-handling
   (with-handlers [(Throwable ex (prompt ex))]
     (with-recursive-body retry []
       (call-with-restarts (make-restarts retry "Retry the evaluation.") body-fn)))))

(defmacro with-interactive-handler [& body]
  `(call-with-interactive-handler (^:once fn [] ~@body)))

(defn handled-eval [form]
  (let [eval (if (::eval e/*msg*)
               (resolve (symbol (::eval e/*msg*)))
               clojure.core/eval)]
    (with-interactive-handler (eval form))))

(defmacro wrapper
  {:style/indent [1]}
  [& bodies]
  (let [bodies (if (vector? (first bodies))
                 (list bodies)
                 bodies)
        wrapped (gensym "wrapped")]
    `(fn [f#]
       (let [~wrapped (or (::original (meta f#))
                          f#)]
         (with-meta (fn
                      ~@(for [[args & body] bodies]
                          (list (vec (next args))
                                `(let [~(first args) ~wrapped]
                                   ~@body))))
           {::original ~wrapped})))))

(defmacro defwrapper [name & bodies]
  {:style/indent [:defn]}
  `(def ~name (wrapper ~@bodies)))

(defwrapper handled-future-call [future-call f]
  (if e/*msg*
    (future-call #(call-with-interactive-handler f))
    (future-call f)))

(defn handled-agent-fn [f]
  (fn [state & args]
    (with-interactive-handler
      (with-recursive-body retry [state state, args args]
        (with-restarts [(:ignore []
                                 :describe "Ignore this action and leave the agent's state unchanged."
                                 state)
                        (:ignore-and-replace [state]
                                             :describe "Ignore this action and provide a new state for the agent."
                                             :arguments #'dgu/read-form
                                             state)
                        (:replace [state]
                                  :describe "Provide a new state for the agent, then retry the action."
                                  :arguments #'dgu/read-form
                                  (retry state args))]
          (apply f state args))))))

(defwrapper handled-send-via [send-via executor agent f & args]
  (with-recursive-body retry []
    (with-restarts [(:restart []
                       :applicable? #(.startsWith (.getMessage %) "Agent is failed")
                       :describe "Restart the agent and retry this action dispatch."
                       (restart-agent agent @agent)
                       (retry))
                    (:restart-with-state [state]
                       :applicable? #(.startsWith (.getMessage %) "Agent is failed")
                       :describe "Provide a new state to restart the agent and retry this action dispatch."
                       :arguments #'dgu/read-form
                       (restart-agent agent state)
                       (retry))]
      (apply send-via
             executor
             agent
             (handled-agent-fn f)
             args))))

(defwrapper restartable-reader [reader filename & opts]
  (with-recursive-body run [filename filename]
    (with-restarts [(:filename [filename]
                       :describe "Provide a filename to open."
                       :applicable? #(instance? java.io.FileNotFoundException %)
                       :arguments (fn [ex] (list (prompt-user "Filename to open: " :file)))
                       (run filename))]
      (apply reader filename opts))))

(defwrapper restartable-ns-resolve
  ([ns-resolve ns sym]
   (clojure.core/ns-resolve ns nil sym))
  ([ns-resolve ns env sym]
   (with-restarts [(:require []
                     :describe (str "Require " ns " and retry.")
                     (require ns)
                     (clojure.core/ns-resolve ns env sym))]
     (ns-resolve ns env sym))))

(defwrapper restartable-push-thread-bindings [push-thread-bindings bindings]
  ;; This function is pretty specialised, because it needs to be aware
  ;; of some internals that usually wouldn't be a concern. Wrapping
  ;; this function affects every time dynamic vars are bound, which is
  ;; something at dgu does a lot of. So, we can't dynamically bind our
  ;; restarts until we've already failed. This should work fine in
  ;; practice, because push-thread-bindings has no restarts of its
  ;; own.
  (try
    (push-thread-bindings bindings)
    (catch IllegalStateException ex
      (if-let [var (extract-non-dynamic-var-name ex)]
        (with-restarts [(:make-dynamic []
                          :describe (str "Make " (pr-str var) " dynamic and retry the evaluation.")
                          (.setDynamic (resolve var) true)
                          (clojure.core/push-thread-bindings bindings))]
          (throw ex))
        (throw ex)))))

(defn run-with-restart-stuff [h {:keys [op code eval] :as msg}]
  (with-redefs [e/queue-eval (fn [session ^Executor executor f]
                               (.execute executor #(f)))]
    (h (assoc msg
              :eval "dont-give-up.nrepl/handled-eval"
              ::eval (:eval msg)))))

(defn choose-restart [{:keys [id restart transport] :as msg}]
  (let [promise (get (deref awaiting-restarts) id)]
    (if promise
      (do (deliver promise restart)
          (t/send transport (response-for msg :status :done)))
      (t/send transport (response-for msg :status :error)))))

(defn answer-prompt [{:keys [id input error transport] :as msg}]
  (let [promise (get (deref awaiting-prompts) id)]
    (if promise
      (do (deliver promise (if input
                             #(do input)
                             #(throw (Exception. "Input cancelled"))))
          (t/send transport (response-for msg :status :done)))
      (t/send transport (response-for msg :status :error)))))

(defwrapper unhandled-test-var [test-var v]
  (dgu/without-handling
    (test-var v)))

(def wrappers
  {#'clojure.core/future-call handled-future-call
   #'clojure.core/send-via handled-send-via
   #'clojure.core/ns-resolve restartable-ns-resolve
   #'clojure.core/push-thread-bindings restartable-push-thread-bindings
   #'clojure.java.io/reader restartable-reader})

(defn wrap-core! []
  (doseq [[v w] wrappers]
    (alter-var-root v w)))

(defn unwrap-core! []
  (doseq [[v _] wrappers]
    (alter-var-root v (comp ::original meta))))

(when-let [ns (find-ns 'user)]
  (binding [*ns* ns]
    (refer 'dont-give-up.nrepl
           :only ['wrap-core! 'unwrap-core!]
           :rename {'wrap-core!   'dgu-wrap-core!
                    'unwrap-core! 'dgu-unwrap-core!})))

;; This one we do automatically, because it's ensuring that we don't
;; interact with it. If we don't have this, then tests can trigger
;; restarts, which feels wrong. Automated tests shouldn't be
;; interactive.
(alter-var-root #'clojure.test/test-var unhandled-test-var)

(defn handle-restarts [h]
  (fn [msg]
    (case (:op msg)
      "eval" (run-with-restart-stuff h msg)
      "restart/choose" (choose-restart msg)
      "restart/answer" (answer-prompt msg)
      (h msg))))

(def middleware `[handle-restarts])

(set-descriptor! #'handle-restarts
                 {:requires #{#'session}
                  :expects #{"eval"}
                  :handles {"restart/choose" {:doc "Select a restart"
                                              :requires {"index" "The index of the reset to choose"}
                                              :optional {}
                                              :returns {}}
                            "restart/answer" {:doc "Provide input to a restart prompt"
                                              :requires {"input" "The input provided to the restart handler"}
                                              :optional {}
                                              :returns {}}}})
