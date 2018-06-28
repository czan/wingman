(ns wingman.base-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wingman.base :refer :all]))

;; Handlers have five basic behaviours that we want to test:

;; 1. The handler's return value is used as the value of the
;; `call-with-handler` form.

(deftest handler-return-values-are-used-directly
  (is (= (call-with-handler (fn [ex] :a)
           #(/ 1 0))
         :a))
  (is (= (call-with-handler (fn [ex] :b)
           #(call-with-restarts (fn [ex])
              (fn [] (/ 1 0))))
         :b))
  (is (= (call-with-handler (fn [ex] :c)
           #(+ (/ 1 0)
               3))
         :c)))

;; 2. If the handler throws an exception, it is propagated as the
;; result of the `call-with-handler` form.

(deftest handler-thrown-exceptions-are-thrown
  (is (thrown-with-msg?
       Exception #"THIS IS A TEST EXCEPTION"
       (call-with-handler (fn [ex] (throw (Exception. "THIS IS A TEST EXCEPTION")))
         #(/ 1 0))))
  (is (thrown-with-msg?
       Exception #"THIS IS A TEST EXCEPTION"
       (call-with-handler (fn [ex] (throw (Exception. "THIS IS A TEST EXCEPTION")))
         #(call-with-restarts (fn [ex])
            (fn [] (/ 1 0)))))))

;; 3. If the handler invokes a restart, the computation continues from
;; the point of that `call-with-restarts` form.

(deftest handler-invoking-restart-restarts-computation
  (is (= (call-with-handler (fn [ex]
                              (let [[r] (list-current-restarts)]
                                (invoke-current-restart r)))
           #(call-with-restarts (fn [ex]
                                  [(make-restart :use-a "" (constantly nil) (constantly :a))])
              (fn []
                (/ 1 0))))
         :a))
  (is (= (call-with-handler (fn [ex]
                              (let [[r] (list-current-restarts)]
                                (invoke-current-restart r :b :c)))
           #(call-with-restarts (fn [ex]
                                  [(make-restart :use-a "" (constantly nil) (fn [x y] [x y]))])
              (fn []
                (/ 1 0))))
         [:b :c])))

;; 4. If the handler invokes `unhandle-exception`, an exception is
;; thrown from the point where it was first seen.

(deftest handler-unhandling-exception-throws
  ;; Note that in this case the exception never actually enters our
  ;; framework, because it is caught and dealt with before it
  ;; propagates high enough.
  (is (= (call-with-handler (fn [ex] (unhandle-exception ex))
           (fn []
             (try
               (/ 1 0)
               (catch Exception ex
                 :a))))
         :a))
  (is (= (call-with-handler (fn [ex] (unhandle-exception ex))
           #(try
              (call-with-restarts (fn [ex] [])
                (fn []
                  (/ 1 0)))
              (catch Exception ex
                :b)))
         :b)))

;; 5. If the handler can't handle something, it `rethrow`s it to a
;; higher handler to make a decision (which might then do any of the
;; above).

(deftest handler-rethrowing-defers-to-higher-handler
  ;; This is just a sanity-check that the handlers get called in the
  ;; right order.
  (is (= (call-with-handler (fn [ex] :a)
           #(call-with-handler (fn [ex] :b)
              (fn [] (/ 1 0))))
         :b))
  (is (= (call-with-handler (fn [ex] :a)
           #(call-with-handler (fn [ex] (rethrow ex))
              (fn [] (/ 1 0))))
         :a))
  (is (thrown-with-msg?
       ArithmeticException #"Divide by zero"
       (call-with-handler (fn [ex] (rethrow ex))
         #(/ 1 0)))))

;; And some other miscellaneous tests.

(deftest list-current-restarts-returns-empty-outside-of-handlers
  (call-with-restarts (fn [ex]
                        [(make-restart :r1 nil nil (constantly nil))])
    #(is (= (map :name (list-current-restarts))
            []))))

(deftest list-current-restarts-returns-current-restarts-inside-handlers
  (call-with-handler (fn [ex]
                       (is (= (map :name (list-current-restarts))
                              [:r1])))
    #(call-with-restarts (fn [ex]
                                [(make-restart :r1 nil nil (constantly nil))])
       (fn []
         (/ 1 0)))))

(deftest rethrow-throws-exceptions
  (is (thrown? Exception
               (rethrow (Exception.))))
  (is (thrown? Exception
               (call-with-handler (fn [ex]
                                    (rethrow ex))
                 #(throw (Exception.)))))
  (is (thrown? Exception
               (call-with-handler (fn [ex]
                                    (rethrow ex))
                 #(call-with-restarts (fn [ex] [])
                    (fn []
                      (throw (Exception.))))))))

(defn read-docstring [string]
  (with-open [stream (clojure.lang.LineNumberingPushbackReader.
                      (java.io.StringReader. string))]
    (loop [state nil, to-eval []]
      (let [c (.read stream)]
        (if (= c -1)
          to-eval
          (condp = (char c)
            \newline (recur nil to-eval)
            \space (recur (when (= :text state) :text) to-eval)
            \> (cond
                 (nil? state)
                 (let [form (read stream)]
                   (recur nil (conj to-eval form)))

                 (= state ";;=")
                 (let [form (read stream)]
                   (assert (not-empty to-eval)
                           "Attempting to check result of an expression before anything has been evaluated")
                   (recur nil (conj (pop to-eval)
                                    (if (= form 'throws)
                                      `(is (~'thrown?
                                            ~(read stream)
                                            ~(last to-eval)))
                                      `(is (~'=
                                            ~form
                                            ~(last to-eval)))))))

                 :else
                 (recur state to-eval))
            \; (recur (str state \;) to-eval)
            \= (recur (str state \=) to-eval)
            (recur :text to-eval)))))))

(defn check-docstring-examples [v]
  (let [ns (:ns (meta v))
        sym (gensym (str ns))
        docstring (:doc (meta v))]
    (when (not-empty docstring)
      (binding [*ns* (create-ns sym)]
        (try
          (clojure.core/refer-clojure)
          (require `[~(ns-name ns) :refer :all])
          (doseq [to-eval (read-docstring docstring)]
            (eval to-eval))
          (finally
            (remove-ns sym)))))))

(deftest docstring-examples
  (doseq [v (vals (ns-publics 'wingman.base))]
    (check-docstring-examples v)))
