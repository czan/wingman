(ns wingman.base-test
  (:require [clojure.test :refer :all]
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
                              (let [[r] (list-restarts)]
                                (invoke-restart r)))
           #(call-with-restarts (fn [ex]
                                  [(make-restart :use-a "" (constantly nil) (constantly :a))])
              (fn []
                (/ 1 0))))
         :a))
  (is (= (call-with-handler (fn [ex]
                              (let [[r] (list-restarts)]
                                (invoke-restart r :b :c)))
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

(deftest list-restarts-returns-empty-outside-of-handlers
  (call-with-restarts (fn [ex]
                        [(make-restart :r1 nil nil (constantly nil))])
    #(is (= (map :name (list-restarts))
            []))))

(deftest list-restarts-returns-current-restarts-inside-handlers
  (call-with-handler (fn [ex]
                       (is (= (map :name (list-restarts))
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

(deftest docstring-examples
  (is (= (call-with-handler #(str "Caught an exception: " (.getMessage %))
           #(/ 1 0))
         "Caught an exception: Divide by zero"))
  (let [[{:keys [name description behaviour]}]
        (call-with-handler (fn [ex] (list-restarts))
          (fn []
            (call-with-restarts
                (fn [ex] [(make-restart :use-value
                                       (str "Use this string instead of " (.getMessage ex))
                                       #(prompt-user "Raw string to use: ")
                                       identity)])
              #(/ 1 0))))]
    (is (= name :use-value))
    (is (= description "Use this string instead of Divide by zero"))
    ;; we can't test make-behaviour, because the instance will be different
    (is (= behaviour identity))))
