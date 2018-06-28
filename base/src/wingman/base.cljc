(ns wingman.base
  (:refer-clojure :exclude [eval])
  #?@(:clj [(:import (wingman.base ScopeResult))]))

#?(:cljs (deftype ScopeResult [scopeId thunk]))

(def ^:private ^:dynamic *handlers* nil)
(def ^:private ^:dynamic *make-restarts* nil)
(def ^:private ^:dynamic *restarts* nil)

(defn call-without-handling
  "Call `thunk` with no active handlers and no current restarts.

  Any exceptions thrown during the execution of `thunk` will not
  invoke handlers from outside of `thunk`. If `thunk` throws an
  exception then it will propagate into handlers above this point.

  In cases without restarts or handlers, `call-without-handling` does
  nothing:

    > (call-without-handling
        #(throw (Exception.)))
    ;;=> throws Exception

  But when handlers are registered, `call-without-handling` allows you
  to isolate a block of code from external handlers. Those higher
  level handlers won't be invoked to handle errors within the
  `call-without-handling` block:

    > (call-with-handler (fn [ex] \"handled\")
        #(call-without-handling
           (fn []
             (try
               (call-with-handler (fn [ex] (rethrow ex))
                 (fn [] (throw (Exception.))))
               (catch Exception ex
                 \"handled in the try/catch\")))))
    ;;=> \"handled in the try/catch\"

  If we had not used `call-without-handling` then the `rethrow` form
  would have deferred to the higher-level handler, which returned a
  different value:

    > (call-with-handler (fn [ex] \"handled\")
        #(try
           (call-with-handler (fn [ex] (rethrow ex))
             (fn [] (throw (Exception.))))
           (catch Exception ex
             \"handled in the try/catch\")))))
    ;;=> \"handled\"

  The most common use case for `call-without-handling` is test code,
  where a test failure should throw the exception and be caught by the
  test runner, even if there are handlers registered."
  [thunk]
  (binding [*handlers* nil
            *make-restarts* nil
            *restarts* nil]
    (thunk)))

(defrecord Restart [name description make-arguments behaviour])

(defn restart?
  "Returns true if a given object represents a restart. Otherwise,
  returns false."
  [obj]
  (instance? Restart obj))

(defn make-restart
  "Create an object representing a restart with the given name,
  description, interactive prompt function, and behaviour when
  invoked."
  [name description make-arguments behaviour]
  (->Restart name description make-arguments behaviour))

(defn rethrow
  "Rethrow an exception, without unwinding the stack any further. This
  will invoke the nearest handler to handle the error. If no handlers
  are available then this is equivalent to `throw`, and the stack will
  be unwound."
  [ex]
  (if (seq *handlers*)
    ((first *handlers*) ex)
    (throw ex)))

(defn unhandle-exception
  "Rethrow an exception, unwinding the stack and propagating it as
  normal. This makes it seem as if the exception was never caught, but
  it may still be caught by handlers/restarts higher in the stack."
  [ex]
  (throw (ScopeResult. nil #(throw ex))))

(defn list-current-restarts
  "Return a list of all current restarts. This function must only be
  called within the dynamic extent of a handler execution."
  []
  *restarts*)

(defn invoke-current-restart
  "Invoke the provided restart instance, with the given arguments. No
  attempt is made to validate that the provided instance is current.

  Always throws an exception, will never return normally."
  [restart & args]
  (throw (ScopeResult. (:id restart) #(apply (:behaviour restart) args))))

(defn- handled-value [id value]
  (throw (ScopeResult. id (constantly value))))

(defn- thrown-value [id value]
  (throw (ScopeResult. id #(throw value))))

(defn run-or-throw [id ^ScopeResult result]
  (if (= (.-scopeId result) id)
    ((.-thunk result))
    (throw result)))

(defn- wrapped-handler [id handler]
  (fn [ex]
    (let [restarts (or *restarts*
                       (vec (mapcat #(% ex) *make-restarts*)))]
      (try (binding [*restarts* restarts
                     *handlers* (next *handlers*)]
             (handled-value id (handler ex)))
           (catch ScopeResult t
             (run-or-throw nil t))
           (catch #?(:clj Throwable
                     :cljs :default) t
             (thrown-value id t))))))

(def ^:private next-id (volatile! 0))

(defn call-with-handler
  "Run `thunk`, registering `handler` to handle any exceptions raised.

  There are five possible outcomes for your handler:

  1. Return a value normally: the `call-with-handler` form will return
  that value.

  2. Throw an exception: the `call-with-handler` form will throw that
  exception.

  3. Invoke a restart, using `invoke-current-restart`: the handler will cease
  executing, and the code of the restart will be invoked, continuing
  to execute from that point.

  4. Invoke `unhandle-exception` on an exception: the exception will
  be thrown from the point where this handler was invoked. This should
  be used with care.

  5. Invoke `rethrow` on an exception: defer the decision to a handler
  higher in the call-stack. If there is no such handler, the exception
  will be thrown (which will behave the same as option 2).

  Notes:

   - The handler will be used for *all* exceptions, so you must be
  careful to `rethrow` exceptions that you are unable to handle.

   - Invoking `unhandle-exception` is primarily useful when working
  with code that uses exceptions to provide fallback behaviour. The
  restart handler mechanism can, in some cases, cause catch clauses to
  be \"skipped\", bypassing exception-based mechanisms. If possible,
  avoid using `unhandle-exception`, as it can result in handlers
  firing multiple times for the same exception.

  Examples:

    > (defn example-body []
        (try
          (letfn [(make-restarts [ex]
                    [(make-restart :use-10 \"\" #(do) #(do 10))])]
            (inc (call-with-restarts make-restarts #(/ 1 0))))
          (catch Exception ex
            -100)))

  Using the above body, the five cases can be demonstrated. Note that
  in all cases except for `unhandle-exception`, the `catch` block is
  skipped over.

    > (call-with-handler (fn [ex] \"gotcha!\")
        example-body)
    ;;=> \"gotcha!\"

    > (call-with-handler (fn [ex] (throw (IllegalArgumentException.)))
        example-body)
    ;;=> throws IllegalArgumentException

    > (letfn [(handler [ex]
                (invoke-current-restart (first (list-current-restarts))))]
        (call-with-handler handler
          example-body))
    ;;=> 11

    > (call-with-handler #(unhandle-exception %)
        example-body)
    ;;=> -100

    > (call-with-handler (fn [ex] \"I don't know!\")
        (fn []
          (call-with-handler #(rethrow %)
            example-body)))
    ;;=> \"I don't know!\""
  {:style/indent [1]}
  [handler thunk]
  (let [id (vswap! next-id inc)]
    (try
      (binding [*handlers* (cons (wrapped-handler id handler) *handlers*)]
        (try
          (thunk)
          (catch ScopeResult t
            (throw t))
          (catch #?(:clj Throwable
                     :cljs :default) t
            (rethrow t))))
      (catch ScopeResult t
        (run-or-throw id t)))))

(defn call-with-restarts
  "Run `thunk`, using `make-restarts` to create restarts for exceptions.

  Run `thunk` within a dynamic extent in which `make-restarts` adds to
  the list of current restarts. If an exception is thrown, then
  `make-restarts` will be invoked, and must return a list of restarts
  applicable to this exception. If no exception is thrown, then
  `make-restarts` will not be invoked.

  Restarts returned by the `make-restarts` function are \"current\"
  until the handler returns in one of the five ways described in
  `call-with-handler`'s docstring. After this time the restart
  instances are no longer able to be restarted.

  For example:

    > (defn div [num den]
        (call-with-restarts
            (fn [ex] [(make-restart :use-value
                                    (str \"Use this string instead of \"
                                         (.getMessage ex))
                                    #(prompt-user \"Raw string to use: \")
                                    identity)])
          #(/ num den)))

    > (call-with-handler (fn [ex]
                           (invoke-current-restart
                             (first (list-current-restarts))
                             100))
        #(div 1 0))
    ;;=> 100"
  {:style/indent [1]}
  [make-restarts thunk]
  (let [id (vswap! next-id inc)]
    (try
      (binding [*make-restarts* (cons (fn [ex]
                                        (map #(assoc % :id id)
                                             (make-restarts ex)))
                                      *make-restarts*)]
        (try
          (thunk)
          (catch ScopeResult t
            (throw t))
          (catch #?(:clj Throwable
                     :cljs :default) t
            (rethrow t))))
      (catch ScopeResult t
        (run-or-throw id t)))))

(defn ^:dynamic prompt-user
  "Prompt the user for some input, in whatever way you can.

  This function will be dynamically rebound by whatever tooling is
  currently active, to prompt the user appropriately.

  Provide a `type` in order to hint to tooling what kind of thing you
  want to read. Legal values of `type` are implementation dependent,
  depending on the tooling in use. Tools should support a minimum of
  `:form` (to read a Clojure form), `:file` (to read a filename), and
  `:options` (to choose an option from a list of options, provided as
  the first argument after `type`).

  Examples:

    > (prompt-user \"Enter a form to evaluate:\" :form)
    ;;=> throws IllegalStateException

  It is up to tools to dynamically rebind this function to something
  appropriate:

    > (binding [prompt-user #(str \"prompting for \" %2 \" with prompt \" %1)]
        (prompt-user \"Enter a form to evaluate:\" :form))
    ;;=> \"prompting for :form with prompt Enter a form to evaluate:\""
  ([prompt]
   (throw (#?(:clj IllegalStateException.
              :cljs js/Error.)
           "In order to prompt the user, a tool must bind #'wingman.base/prompt-user.")))
  ([prompt type & args]
   (throw (#?(:clj IllegalStateException.
              :cljs js/Error.)
           "In order to prompt the user, a tool must bind #'wingman.base/prompt-user."))))
