(ns dont-give-up.core)

(defrecord Restart [name describe make-arguments behaviour])

(defn use-restart [name & args]
  (throw (ex-info "Use this restart"
                  {::type :use-restart
                   ::name name
                   ::args args})))

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

(declare ^:dynamic *default-handler*)

(def ^:dynamic *handlers* [#'*default-handler*])
(def ^:dynamic *restarts* [])

(defn signal [ex & args]
  (if (seq *handlers*)
    (let [[handler & others] *handlers*]
      (binding [*handlers* others]
        (apply handler ex args)))
    (throw ex)))

(defn either-throw-or-signal [t]
  (if (our-throwable? t)
    (throw t)
    (signal t)))

(defn ^:dynamic *default-handler* [ex & args]
  (println "Error:" (.getMessage ex))
  (println)
  (println "These are the currently-available restarts:")
  (doseq [[i restart] (map-indexed vector *restarts*)]
    (println (format "  [%s] %s%s"
                     (inc i)
                     (:name restart)
                     (if-let [desc (not-empty (apply (:describe restart) ex args))]
                       (str " - " desc)
                       ""))))
  (println "  [q] Abort - Rethrow the exception.")
  (loop []
    (print "Enter a restart to use: ")
    (flush)
    (let [result (read-string (read-line))]
      (cond
        (= 'q result)
        (throw ex)
        
        (<= 1 result (count *restarts*))
        (let [restart (get *restarts* (dec result))]
          (apply use-restart restart
                 (apply (:make-arguments restart) ex args)))
        
        :else
        (do (println "Not a valid restart.")
            (recur))))))

(defn with-handler-fn [thunk handler]
  (let [id (gensym "handle-id")]
    (binding [*handlers* (cons (fn [ex & args]
                                 (try (handled-value id (apply handler ex args))
                                      (catch Throwable t
                                        (if (our-throwable? t)
                                          (throw t)
                                          (thrown-value id t)))))
                               *handlers*)]
      (try
        (try
          (thunk)
          (catch Throwable t
            (either-throw-or-signal t)))
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
              (either-throw-or-signal e))))))))

(defn with-restarts-fn [thunk restarts]
  (binding [*restarts* (into (vec restarts) *restarts*)]
    (try
      (try
        (thunk)
        (catch Throwable t
          (either-throw-or-signal t)))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            (not= (::type data) :use-restart)
            (either-throw-or-signal e)

            (instance? Restart (::name data))
            (let [restart (::name data)]
              (apply (:behaviour restart) (::args data)))

            :else
            (let [restart-name (::name data)
                  restart (->> *restarts*
                               (filter #(= (:name %) restart-name))
                               first)]
              (if restart
                (apply (:behaviour restart) (::args data))
                (signal (IllegalArgumentException. (str "No restart registered for " restart-name)))))))))))

(defn read-unevaluated-value [ex & args]
  (print "Enter a value to be used (unevaluated): ")
  (flush)
  (try (let [x [(read-string (read-line))]]
         (println)
         x)
       (catch Throwable t
         (println)
         (print "Couldn't read a value. Aborting.")
         (flush)
         (signal t))))

(defn read-and-eval-value [ex & args]
  (print "Enter a value to be used (evaluated): ")
  (flush)
  (try (let [x [(eval (read-string (read-line)))]]
         (println)
         x)
       (catch Throwable t
         (println)
         (print "Couldn't read a value. Aborting.")
         (flush)
         (signal t))))

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
