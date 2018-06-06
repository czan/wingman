(ns dont-give-up.interface
  (:require [dont-give-up.core :as dgu]))

(defn find-restarts
  "Return a list of all dynamically-bound restarts with the provided
  name. If passed an instance of `Restart`, search by equality."
  [restart]
  (if (dgu/restart? restart)
    (filter #(= % restart) (dgu/list-restarts))
    (filter #(= (:name %) restart) (dgu/list-restarts))))

(defn find-restart
  "Return the first dynamically-bound restart with the provided name.
  If passed an instance of `Restart`, search by equality."
  [restart]
  (first (find-restarts restart)))

(defn invoke-restart
  "Invoke the given restart, after validating that it is active. If an
  object satisfying `dgu/restart?` is passed, check that it is active,
  otherwise invoke the most recently defined restart with the given
  name.

  This function will never return a value. This function will always
  throw an exception."
  [name & args]
  (if-let [restart (find-restart name)]
    (apply dgu/invoke-restart restart args)
    (throw (IllegalArgumentException. (str "No restart registered for " name)))))

(defn rethrow
  "Rethrow an exception, propagating it to a higher error handler
  without unwinding the stack. If there are no further error handlers,
  unwind the stack."
  [ex]
  (dgu/rethrow ex))

(defn read-form
  "Read an unevaluated form from the user, and return it for use as a
  restart's arguments;"
  [ex]
  [(try (read-string (dgu/prompt-user "Enter a value to be used (unevaluated): " :form))
        (catch Exception _
          (throw ex)))])

(defn read-and-eval-form
  "Read a form from the user, and return the evaluated result for use
  as a restart's arguments."
  [ex]
  [(dgu/eval* (try (read-string (dgu/prompt-user "Enter a value to be used (evaluated): " :form))
                   (catch Exception _
                     (throw ex))))])

(defmacro with-cleared-restarts [& body]
  `(dgu/call-with-cleared-restarts (fn [] ~@body)))

(defmacro with-restarts
  "Run `body`, providing `restarts` as dynamic restarts to handle
  errors which occur while executing `body`.

  For example, a simple restart to use a provided value would look
  like this:

      (with-restarts [(:use-value [value] value)]
        (/ 1 0))

  This would allow a handler to invoke `(invoke-restart :use-value 10)`
  to recover from this exception, and to return `10` as the result of
  the `with-restarts` form.

  In addition, restarts can have three extra attributes defined:

  1. `:applicable?` specifies a predicate which tests whether this
  restart is applicable to this exception type. It defaults
  to `(fn [ex] true)`.

  2. `:describe` specifies a function which will convert the exception
  into an explanation of what this restart will do. As a shortcut, you
  may use a string literal instead, which will be converted into a
  function returning that string. It defaults to `(fn [ex] \"\")`.

  3. `:arguments` specifies a function which will return arguments for
  this restart. This function is only ever used interactively, and
  thus should prompt the user for any necessary information to invoke
  this restart. It defaults to `(fn [ex] nil)`.

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
  \"closest\" one will be invoked by a call to `invoke-restart`.

  Restart names can be any value that is not an instance of
  `dont-give-up.core.Restart`, but it is recommended to use keywords
  as names."
  {:style/indent [1 [[:defn]] :form]}
  [restarts & body]
  (let [ex (gensym "ex")]
    `(dgu/call-with-restarts
         (fn [~ex]
           (remove nil?
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
                                    `(when (~applicable? ~ex)
                                       (dgu/make-restart
                                        ~name
                                        (let [d# ~describe]
                                          (if (string? d#)
                                            d#
                                            (d# ~ex)))
                                        (fn []
                                          (~make-arguments ~ex))
                                        (fn ~(vec args)
                                          ~@body))))))))
                          restarts)))
       (^:once fn [] ~@body))))

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

  1. invoke `invoke-restart`, which will restart execution from the
  specified restart

  2. invoke `rethrow`, which will either defer to a handler higher up
  the call-stack, or propagate the exception via standard JVM
  mechanisms.

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
    `(dgu/call-with-handler
      (fn [~ex-sym]
        (cond
          ~@(mapcat (fn [[type arg & body]]
                      (if (seq body)
                        `((instance? ~type ~ex-sym)
                          (let [~arg ~ex-sym]
                            ~@body))
                        `((instance? ~type ~ex-sym)
                          nil)))
                    handlers)
          :else (dgu/rethrow ~ex-sym)))
      (^:once fn [] ~@body))))
