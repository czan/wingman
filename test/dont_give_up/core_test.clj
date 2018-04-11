(ns dont-give-up.core-test
  (:require [clojure.test :refer :all]
            [dont-give-up.core :refer :all]))

(use-fixtures :once (fn [f]
                      (binding [*handlers* []
                                *restarts* []]
                        ;; clear things so the REPL binding doesn't
                        ;; make everything fail
                        (f))))

(deftest handlers-should-use-the-first-matching-clause
  (is (= :value
         (with-handlers [(ArithmeticException ex
                           :value)
                         (Exception ex)]
           (/ 1 0))))
  (is (= nil
         (with-handlers [(Exception ex)
                         (ArithmeticException ex
                           :value)]
           (/ 1 0)))))

(deftest handlers-should-work-even-above-try-catch
  (is (= 11
         (with-handlers [(Exception ex
                           (use-restart :use-value 10))]
           (try
             (+ (with-restarts [(:use-value [value]
                                  value)]
                  (throw (Exception.)))
                1)
             (catch Exception ex))))))

(deftest restarts-from-exceptions-should-work
  (is (= 10
         (with-handlers [(Exception ex
                           (use-restart :use-value 10))]
           (with-restarts [(:use-value [value] value)]
             (throw (RuntimeException.)))))))

(deftest restarts-should-bubble-up-if-unhandled
  (is (= 10
         (with-handlers [(Exception ex (use-restart :use-value 10))]
           (with-handlers [(ArithmeticException ex)]
             (with-restarts [(:use-value [value] value)]
               (throw (RuntimeException.)))))))
  (is (= 10
         (with-handlers [(Exception ex (use-restart :use-value 10))]
           (with-handlers [(ArithmeticException ex (signal ex))]
             (with-restarts [(:use-value [value] value)]
               (throw (ArithmeticException.))))))))

(deftest restarts-should-use-the-most-specific-named-restart
  (is (= 10
         (with-handlers [(Exception ex (use-restart :use-default))]
           (with-restarts [(:use-default [] 13)]
             (with-restarts [(:use-default [] 10)]
               (throw (RuntimeException.))))))))

(deftest restarts-should-restart-from-the-right-point
  (is (= 0
         (with-handlers [(Exception ex (use-restart :use-zero))]
           (with-restarts [(:use-zero [] 0)]
             (inc (with-restarts [(:use-one [] 1)]
                    (throw (Exception.)))))))))

(deftest handlers-returning-values-should-return-at-the-right-place
  (is (= 2
         (with-handlers [(RuntimeException ex 2)]
           (+ 1 (with-handlers [(ArithmeticException ex)]
                  (throw (RuntimeException.))))))))

(deftest handlers-should-not-modify-exceptions-when-not-handling
  (let [ex (Exception.)]
    (is (= ex
           (try
             (with-handlers [(ArithmeticException ex 10)]
               (throw ex))
             (catch Exception e e))))
    (is (= ex
           (try
             (with-handlers [(ArithmeticException ex 10)]
               (throw ex))
             (catch Exception e e))))))

(deftest restarts-should-not-modify-exceptions-when-not-handling
  (let [ex (Exception.)]
    (is (= ex
           (try
             (with-restarts [(:use-value [value] value)]
               (throw ex))
             (catch Exception e e))))
    (is (= ex
           (try
             (with-restarts [(:use-value [value] value)]
               (with-restarts [(:use-value [value] value)]
                 (throw ex)))
             (catch Exception e e))))))

(deftest handlers-throwing-exceptions-should-be-catchable
  (is (= Exception
         (try
           (with-handlers [(RuntimeException ex (throw (Exception.)))]
             (throw (RuntimeException.)))
           (catch Exception ex
             (.getClass ex)))))
  (is (= Exception
         (try
           (with-handlers [(RuntimeException ex (throw (Exception.)))]
             (throw (RuntimeException.)))
           (catch Exception ex
             (.getClass ex)))))
  (is (= Exception
         (try
           (with-handlers [(RuntimeException ex (throw (Exception.)))]
             (with-restarts [(:use-value [value] value)]
               (throw (RuntimeException.))))
           (catch Exception ex
             (.getClass ex)))))
  (is (= Exception
         (try
           (with-handlers [(RuntimeException ex (throw (Exception.)))]
             (with-restarts [(:use-value [value] value)]
               (throw (RuntimeException.))))
           (catch Exception ex
             (.getClass ex)))))
  (is (= Exception
         (with-handlers [(Exception ex 10)]
           (try
             (with-handlers [(RuntimeException ex (throw (Exception.)))]
               (with-restarts [(:use-value [value] value)]
                 (throw (RuntimeException.))))
             (catch Exception ex
               (.getClass ex)))))))

(deftest restarts-should-go-away-during-handler
  (is (= 0
         (with-handlers [(Exception ex
                           (use-restart :try-1))]
           (with-restarts [(:try-1 []
                             (count *restarts*))]
             (throw (RuntimeException.)))))))

(def ^:dynamic *x* nil)

(deftest dynamic-bindings-should-be-set-for-handlers-and-restarts
  (is (= [1 0]
         (binding [*x* 0]
           (with-handlers [(Exception ex
                             (use-restart :x *x*))]
             (binding [*x* 1]
               (with-restarts [(:x [local] [*x* local])]
                 (throw (Exception.)))))))))
