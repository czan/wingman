(ns wingman.core
  (:require [wingman.base :as w]))

(defn list-restarts
  {:doc (:doc (meta #'w/list-current-restarts))}
  []
  (w/list-current-restarts))

(defn unhandle-exception
  {:doc (:doc (meta #'w/unhandle-exception))}
  [ex]
  (w/unhandle-exception ex))

(defn rethrow
  {:doc (:doc (meta #'w/rethrow))}
  [ex]
  (w/rethrow ex))

(defn find-restarts
  "Return a list of all dynamically-bound restarts with the provided
  name. If passed an instance of `Restart`, search by equality."
  [restart]
  (if (w/restart? restart)
    (filter #(= % restart) (w/list-current-restarts))
    (filter #(= (:name %) restart) (w/list-current-restarts))))

(defn find-restart
  "Return the first dynamically-bound restart with the provided name.
  If passed an instance of `Restart`, search by equality."
  [restart]
  (first (find-restarts restart)))

(defn invoke-restart
  "Invoke the given restart, after validating that it is active. If an
  object satisfying `w/restart?` is passed, check that it is active,
  otherwise invoke the most recently defined restart with the given
  name.

  This function will never return a value. This function will always
  throw an exception."
  [name & args]
  (if-let [restart (find-restart name)]
    (apply w/invoke-current-restart restart args)
    (throw (IllegalArgumentException. (str "No restart registered for " name)))))

(defn read-form
  "Read an unevaluated form from the user, and return it for use as a
  restart's arguments;"
  [ex]
  [(try (read-string (w/prompt-user "Enter a value to be used (unevaluated): " :form))
        (catch Exception _
          (throw ex)))])

(defn read-and-eval-form
  "Read a form from the user, and return the evaluated result for use
  as a restart's arguments."
  [ex]
  [(eval (try (read-string (w/prompt-user "Enter a value to be used (evaluated): " :form))
              (catch Exception _
                (throw ex))))])

(defmacro without-handling
  "Run `body` with no active handlers and no current restarts. Any
  exceptions raised by `thunk` will propagate normally. Note that an
  exception raised by this call will be handled normally."
  [& body]
  `(w/call-without-handling (fn [] ~@body)))

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
  `wingman.base.Restart`, but it is recommended to use keywords
  as names."
  {:style/indent [1 [[:defn]] :form]}
  [restarts & body]
  (let [ex (gensym "ex")]
    `(w/call-with-restarts
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
                                       (w/make-restart
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

  See `wingman.base/call-with-handler` for more details about
  handler functions."
  {:style/indent [1 [[:defn]] :form]}
  [handlers & body]
  (let [ex-sym (gensym "ex")]
    `(w/call-with-handler
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
          :else (w/rethrow ~ex-sym)))
      (^:once fn [] ~@body))))

(defmacro try'
  "Like `try`, but registers handlers instead of normal catch clauses.
  Restarts can be invoked from the defined handlers.

  For example:

    (try'
      (send a conj 30)
      (catch Exception ex
        (invoke-restart :restart-with-state nil))
      (finally
        (send a conj 40)))

  See `with-handlers` for more detail about what handlers can do."
  {:style/indent [0]}
  [& body-and-clauses]
  (letfn [(has-head? [head form]
            (and (seq? form)
                 (= (first form) head)))
          (wrap-finally [form finally]
            (if finally
              `(try ~form ~finally)
              form))]
    (loop [stage   :body
           body    []
           clauses []
           finally nil
           forms   body-and-clauses]
      (case stage
        :body  (if (empty? forms)
                 `(do ~@body)
                 (condp has-head? (first forms)
                   'catch (recur :catch body clauses finally forms)
                   (recur :body (conj body (first forms)) clauses finally (next forms))))
        :catch (if (empty? forms)
                 (recur :done body clauses finally forms)
                 (condp has-head? (first forms)
                   'catch   (recur :catch body (conj clauses (next (first forms))) finally (next forms))
                   'finally (recur :done body clauses (first forms) (next forms))
                   (throw (IllegalArgumentException.
                           "After the first catch, everything must be a catch or a finally in a try' form."))))
        :done  (if (empty? forms)
                 (wrap-finally
                  `(with-handlers ~clauses
                     ~@body)
                  finally)
                 (throw (IllegalArgumentException.
                         "Can't have anything following the finally clause in a try' form.")))))))
