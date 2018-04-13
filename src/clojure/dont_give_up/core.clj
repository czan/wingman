(ns dont-give-up.core
  (:import [dont_give_up UseRestart HandlerResult]))

(def ^:dynamic *handlers* [])
(def ^:dynamic *restarts* [])

(defrecord Restart [name describe applicable? make-arguments behaviour])

(defn rethrow
  "Rethrow an exception, within the restart machinery. This will
  invoke the nearest handler to handle the error. If no handlers are
  available, this is equivalent to `throw`."
  [ex]
  (if (seq *handlers*)
    ((first *handlers*) ex)
    (throw ex)))

(defn- applicable-restarts
  "Filter a seq of restarts, and return the ones applicable to the
  given exception as a vector."
  [restarts ex]
  (filterv (fn [restart]
             ((:applicable? restart) ex))
           restarts))

(defn find-restarts
  "Return a list of all dynamically-bound restarts with the provided
  name. If passed an instance of `Restart`, search by equality."
  [restart]
  (if (instance? Restart restart)
    (filter #(= % restart) *restarts*)
    (filter #(= (:name %) restart) *restarts*)))

(defn use-restart
  "Use the provided restart, with the given arguments. The restart
  provided can be a name, in which case it will be looked up and the
  most recently-bound matching restart will be used. If an instance of
  `Restart` is provided, it must be bound higher on the call-stack.

  Always throws an exception, will never return normally."
  [restart & args]
  (let [restart-instance (first (find-restart restart))]
    (throw (if restart-instance
             (UseRestart. restart-instance args)
             (IllegalArgumentException. (str "No restart registered for " restart))))))

(defn- handled-value [id value]
  (throw (HandlerResult. id #(do value))))

(defn- thrown-value [id value]
  (throw (HandlerResult. id #(throw value))))

(defn with-handler-fn
  "Run `thunk`, using `handler` to handle any exceptions raised.
  Prefer to use `with-handlers` instead of this function. "
  [thunk handler]
  (let [id (gensym "handle-id")
        definition-frame (clojure.lang.Var/getThreadBindingFrame)]
    (try
      (binding [*handlers* (cons (fn [ex]
                                   (let [restarts (applicable-restarts *restarts* ex)
                                         execution-frame (clojure.lang.Var/getThreadBindingFrame)]
                                     (try (clojure.lang.Var/resetThreadBindingFrame definition-frame)
                                          (binding [*restarts* restarts]
                                            (handled-value id (handler ex)))
                                          (catch UseRestart t
                                            (throw t))
                                          (catch HandlerResult t
                                            (throw t))
                                          (catch Throwable t
                                            (thrown-value id t))
                                          (finally
                                            (clojure.lang.Var/resetThreadBindingFrame execution-frame)))))
                                 *handlers*)]
        (try
          (thunk)
          (catch ThreadDeath t
            (throw t))
          (catch UseRestart t
            (throw t))
          (catch HandlerResult t
            (throw t))
          (catch Throwable t
            (rethrow t))))
      (catch HandlerResult t
        (if (= (.-handlerId t) id)
          ((.-thunk t))
          (throw t))))))

(defn with-restarts-fn
  "Register restarts which can be invoked from handlers. Prefer to use
  `with-restarts` instead of this function."
  [thunk restarts]
  (try
    (binding [*restarts* (concat restarts *restarts*)]
      (try
        (thunk)
        (catch ThreadDeath t
            (throw t))
        (catch UseRestart t
          (throw t))
        (catch HandlerResult t
          (throw t))
        (catch Throwable t
          (rethrow t))))
    (catch UseRestart t
      (if (some #(= % (.-restart t)) restarts)
        (apply (:behaviour (.-restart t)) (.-args t))
        (throw t)))))

(defn- prompt-with-stdin [prompt]
  (print prompt)
  (flush)
  (read-line))

(defn- prompt-user [prompt]
  (let [f (or (ns-resolve 'dont-give-up.middleware 'prompt-for-input)
              prompt-with-stdin)]
    (f prompt)))

(defn- eval* [form]
  (let [f (or (ns-resolve 'dont-give-up.middleware 'handled-eval)
              eval)]
    (f form)))

(defn read-unevaluated-value
  "Read an unevaluated value from the user, and return it for use as a
  restart's arguments;"
  [ex]
  [(try (read-string (prompt-user "Enter a value to be used (unevaluated): "))
        (catch Exception _
          (throw ex)))])

(defn read-and-eval-value
  "Read a value from the user, and return the evaluated result for use
  as a restart's arguments."
  [ex]
  [(eval* (try (read-string (prompt-user "Enter a value to be used (evaluated): "))
               (catch Exception _
                 (throw ex))))])

(defmacro with-restarts
  "Run `body`, providing `restarts` as dynamic restarts to handle
  errors which occur while executing `body`.

  For example, a simple restart to use a provided value would look
  like this:

      (with-restarts [(:use-value [value] value)]
        (/ 1 0))

  This would allow a handler to invoke `(use-restart :use-value 10)`
  to recover from this exception, and to return `10` as the result of
  the `with-restarts` form.

  In addition, restarts can have three extra attributes defined:

  1. `:applicable?` specifies a predicate which tests whether this
  restart is applicable to this exception type. It defaults
  to `(constantly true)`, under the assumption that restarts are
  always applicable.

  2. `:describe` specifies a function which will convert the exception
  into an explanation of what this restart will do. As a shortcut, you
  may use a string literal instead, which will be converted into a
  function returning that string. It defaults to `(constantly \"\")`.

  3. `:arguments` specifies a function which will return arguments for
  this restart. This function is only ever used interactively, and
  thus should prompt the user for any necessary information to invoke
  this restart. It defaults to `(constantly nil)`.

  Here is an example of the above restart using these attributes:

      (with-restarts [(:use-value [value]
                         :describe \"Provide a value to use.\"
                         :arguments #'read-unevaluated-value
                         value)]
        (/ 1 0))

  Restarts are invoked in the same dynamic context in which they were
  defined. The stack is unwound to the level of the `with-restarts`
  form, and the restart is invoked.

  Multiple restarts with the same name can be defined, but the
  \"closest\" one will be invoked by a call to `use-restart`.

  Restart names can be any value that is not an instance of
  `dont-give-up.core.Restart`, but it is recommended to use keywords
  as names."
  {:style/indent [1 [[:defn]] :form]}
  [restarts & body]
  `(with-restarts-fn
     (fn ^:once [] ~@body)
     (lazy-seq
      ~(mapv (fn [restart]
               (if (symbol? restart)
                 restart
                 (let [[name args & body] restart]
                   (loop [body body
                          describe `(constantly "")
                          applicable? `(constantly true)
                          make-arguments `(constantly nil)]
                     (cond
                       (= (first body) :describe)
                       (recur (nnext body)
                              (second body)
                              applicable?
                              make-arguments)

                       (= (first body) :applicable?)
                       (recur (nnext body)
                              describe
                              (second body)
                              make-arguments)

                       (= (first body) :arguments)
                       (recur (nnext body)
                              describe
                              applicable?
                              (second body))

                       :else
                       `(->Restart ~name
                                   (let [d# ~describe]
                                     (if (string? d#)
                                       (constantly d#)
                                       d#))
                                   ~applicable?
                                   ~make-arguments
                                   (fn ~(vec args)
                                     ~@body)))))))
             restarts))))

(defmacro with-handlers
  "Run `body`, using `handlers` to handle any exceptions which are
  raised during `body`'s execution.

  For example, here is how to use `with-handlers` to replace
  try/catch:

      (with-handlers [(Exception ex (.getMessage ex))]
        (/ 1 0))
      ;; => \"Divide by zero\"

  Similarly to try/catch, multiple handlers can be defined for
  different exception types, and the first matching handler will be
  run to handle the exception.

  Handlers can have only one of four outcomes:

  1. invoke `use-restart`, which will restart execution from the
  specified restart

  2. invoke `rethrow`, which will defer to a handler higher up the
  call-stack, or `throw` if this is the highest handler

  3. return a value, which will be the value returned from the
  `with-handler-fn` form

  4. throw an exception, which will be thrown as the result of the
  `with-handler-fn` form

  Conceptually, options `1` and `2` process the error without
  unwinding the stack, and options `3` and `4` unwind the stack up
  until the handler.

  If `handler` is invoked, the dynamic var context will be set to be
  as similar as possible to the dynamic context when `with-hander-fn`
  is called. This simulates the fact that the handler conceptually
  executes at a point much further up the call stack.

  Any dynamic state captured in things other than vars (e.g.
  `ThreadLocal`s, open files, mutexes) will be in the state of the
  `with-restarts` execution nearest to the thrown exception."
  {:style/indent [1 [[:defn]] :form]}
  [handlers & body]
  (let [ex-sym (gensym "ex")]
    `(with-handler-fn
      (fn ^:once [] ~@body)
      (fn [~ex-sym]
        (cond
          ~@(mapcat (fn [[type arg & body]]
                      `((instance? ~type ~ex-sym)
                        (let [~arg ~ex-sym]
                          ~@body)))
                    handlers)
          :else (rethrow ~ex-sym))))))
