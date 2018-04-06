(ns dont-give-up.core)

(def ^:dynamic *handlers* [])
(def ^:dynamic *restarts* [])

(defrecord Restart [name describe make-arguments behaviour])

(defn signal [ex & args]
  (if (seq *handlers*)
    (let [[handler & others] *handlers*]
      (binding [*handlers* others]
        (apply handler ex args)))
    (throw ex)))

(defn use-restart [name & args]
  (let [restart (if (instance? Restart name)
                  name
                  (->> *restarts*
                       (filter #(= (:name %) name))
                       first))]
    (if restart
      (throw (ex-info "Use this restart"
                      {::type :use-restart
                       ::restart restart
                       ::args args}))
      (signal (IllegalArgumentException. (str "No restart registered for " name))))))

(defn handled-value [id value]
  (throw (ex-info "Return this handled value"
                  {::type :handled-value
                   ::id id
                   ::value value})))

(defn thrown-value [id value]
  (throw (ex-info "Throw a value"
                  {::type :thrown-value
                   ::id id
                   ::value value})))

(defn our-throwable? [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (::type (ex-data t))))

(defn either-throw-or-signal [t]
  (if (our-throwable? t)
    (throw t)
    (signal t)))

(defn with-handler-fn [thunk handler]
  (let [id (gensym "handle-id")]
    (try
      (binding [*handlers* (cons (fn [ex & args]
                                   (try (handled-value id (apply handler ex args))
                                        (catch Throwable t
                                          (if (our-throwable? t)
                                            (throw t)
                                            (thrown-value id t)))))
                                 *handlers*)]
        (try
          (thunk)
          (catch Throwable t
            (either-throw-or-signal t))))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            (and (= (::type data) :handled-value)
                 (= (::id data) id))
            (::value data)

            (and (= (::type data) :thrown-value)
                 (= (::id data) id))
            (throw (::value data))

            :else
            (either-throw-or-signal e)))))))

(defn with-restarts-fn [thunk restarts]
  (try
    (binding [*restarts* (into (vec restarts) *restarts*)]
      (try
        (thunk)
        (catch Throwable t
          (either-throw-or-signal t))))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (= (::type data) :use-restart)
          (apply (:behaviour (::restart data)) (::args data))
          (either-throw-or-signal e))))))

(defn read-unevaluated-value [ex & args]
  (print "Enter a value to be used (unevaluated): ")
  (flush)
  (try (let [x [(read-string (read-line))]]
         (println)
         x)
       (catch Throwable t
         (println)
         (println "Couldn't read a value. Aborting.")
         (flush)
         (signal ex))))

(defn read-and-eval-value [ex & args]
  (print "Enter a value to be used (evaluated): ")
  (flush)
  (try (let [x [(eval (read-string (read-line)))]]
         (println)
         x)
       (catch Throwable t
         (println)
         (println "Couldn't read a value. Aborting.")
         (signal ex))))

(defmacro with-restarts
  {:style/indent [1 [[:defn]] :form]}
  [restarts & body]
  `(with-restarts-fn
    (fn ^:once [] ~@body)
    ~(mapv (fn [restart]
             (if (symbol? restart)
               restart
               (let [[name args & body] restart]
                 (loop [body body
                        describe `(constantly "")
                        make-arguments `(constantly nil)]
                   (cond
                     (= (first body) :describe)
                     (recur (nnext body)
                            (second body)
                            make-arguments)

                     (= (first body) :arguments)
                     (recur (nnext body)
                            describe
                            (second body))

                     :else
                     `(->Restart ~name
                                 (let [d# ~describe]
                                   (if (string? d#)
                                     (constantly d#)
                                     d#))
                                 ~make-arguments
                                 (fn ~(vec args)
                                   ~@body)))))))
           restarts)))

(defmacro with-handlers
  {:style/indent [1 [[:defn]] :form]}
  [handlers & body]
  (let [ex-sym (gensym "ex")
        args-sym (gensym "args")]
    `(with-handler-fn
      (fn ^:once [] ~@body)
      (fn [~ex-sym & ~args-sym]
        (cond
          ~@(mapcat (fn [[type args & body]]
                      `((instance? ~type ~ex-sym)
                        (apply (fn ~(vec args)
                                 ~@body)
                               ~ex-sym ~args-sym)))
                    handlers)
          :else (apply signal ~ex-sym ~args-sym))))))
