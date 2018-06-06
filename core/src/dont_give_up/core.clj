(ns dont-give-up.core
  (:refer-clojure :exclude [eval])
  (:import (dont_give_up.core UseRestart
                              HandlerResult
                              UnhandledException)))

(def ^:private ^:dynamic *handlers* nil)
(def ^:private ^:dynamic *make-restarts* nil)
(def ^:private ^:dynamic *restarts* nil)

(defn call-without-handling
  "Call `thunk` with no active handlers and no current restarts. Any
  exceptions raised by `thunk` will propagate normally. Note that an
  exception raised by this call will be handled normally."
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
  "Create an instance of `Restart`. When using the `with-restarts`
  macro it's unnecessary to use this function. It is only necessary
  when using `call-with-restarts` to create restarts."
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
  (throw (UnhandledException. ex)))

(defn list-restarts
  "Return a list of all dynamically-bound restarts."
  []
  *restarts*)

(defn invoke-restart
  "Use the provided restart, with the given arguments. No attempt is
  made to validate that the provided restart is currently active.

  Always throws an exception, will never return normally."
  [restart & args]
  (throw (UseRestart. restart args)))

(defn- handled-value [id value]
  (throw (HandlerResult. id (constantly value))))

(defn- thrown-value [id value]
  (throw (HandlerResult. id #(throw value))))

(defn- wrapped-handler [id handler]
  (fn [ex]
    (let [restarts (or *restarts*
                       (vec (mapcat #(% ex) *make-restarts*)))]
      (try (binding [*restarts* restarts
                     *handlers* (next *handlers*)]
             (handled-value id (handler ex)))
           (catch UseRestart t
             (throw t))
           (catch HandlerResult t
             (throw t))
           (catch UnhandledException t
             (throw (.-exception t)))
           (catch Throwable t
             (thrown-value id t))))))

(def ^:private next-handler-id (volatile! 0))

(defn call-with-handler
  "Run `thunk`, using `handler` to handle any exceptions raised.
  Prefer to use `with-handlers` instead of this function.

  Note that the handler will be used for *all* exceptions, so you must
  be careful to `rethrow` exceptions that you can't handle."
  {:style/indent [1]}
  [handler thunk]
  (let [id (vswap! next-handler-id inc)]
    (try
      (binding [*handlers* (cons (wrapped-handler id handler) *handlers*)]
        (try
          (thunk)
          (catch UseRestart t
            (throw t))
          (catch HandlerResult t
            (throw t))
          (catch Exception t
            (rethrow t))))
      (catch HandlerResult t
        (if (= (.-handlerId t) id)
          ((.-thunk t))
          (throw t))))))

(def ^:private next-restart-id (volatile! 0))

(defn call-with-restarts
  "This is an advanced function. Prefer `with-restarts` where possible.

  Run `thunk` within a dynamic extent in which `make-restarts` adds to
  the list of current restarts. If an exception is thrown, then
  `make-restarts` will be invoked, and must return a list of restarts
  applicable to this exception. If no exception is thrown, then
  `make-restarts` will not be invoked.

  For example:

      (call-with-restarts
        (fn [ex] [(make-restart :use-value
                                \"Use this value\"
                                #(read-form ex)
                                identity)])
        (^:once fn []
          (/ 1 0)))

  You should usually use the `with-restarts` macro, but if you need to
  dynamically vary your restarts depending on the type of exception
  that is thrown, `call-with-restarts` will let you register restarts
  only after the exception has been thrown. Generally you should use
  the `:applicable?` property of the `with-restarts` macro, but using
  `call-with-restarts` lets you do things like this:

      (defn resolve [symbol]
        (call-with-restarts
          (fn [ex]
            (for [namespace (all-ns)
                  :when (ns-resolve namespace symbol)]
              (make-restart (keyword (str \"use-\" namespace))
                            (str \"Resolve value from \" namespace)
                            (constantly nil)
                            #(ns-resolve namespace symbol))))
          (^:once fn []
            (resolve symbol))))

  Which provides a restart for each namespace that has a var with the
  correct name."
  {:style/indent [1]}
  [make-restarts thunk]
  (let [id (vswap! next-restart-id inc)]
    (try
      (binding [*make-restarts* (cons (fn [ex]
                                        (map #(assoc % :id id)
                                             (make-restarts ex)))
                                      *make-restarts*)]
        (try
          (thunk)
          (catch UseRestart t
            (throw t))
          (catch HandlerResult t
            (throw t))
          (catch Exception t
            (rethrow t))))
      (catch UseRestart t
        (if (= (:id (.-restart t)) id)
          (apply (:behaviour (.-restart t)) (.-args t))
          (throw t))))))

(defn ^:dynamic prompt-user
  "Prompt the user for some input, in whatever way you can.

  This function will be dynamically rebound by whatever tooling is
  currently active, to prompt the user appropriately.

  Provide a `type` in order to hint to tooling what kind of thing you
  want to read. Legal values of `type` are implementation dependent,
  depending on the tooling in use. Tools should support a minimum of
  `:form` (to read a Clojure form), `:file` (to read a filename), and
  `:options` (to choose an option from a list of options, provided as
  the first argument after `type`)."
  ([prompt]
   (throw (IllegalStateException. "In order to prompt the user, a tool must redefine this function.")))
  ([prompt type & args]
   (throw (IllegalStateException. "In order to prompt the user, a tool must redefine this function."))))
