(ns dont-give-up.core-test
  (:require [clojure.test :refer :all]
            [dont-give-up.core :refer :all]))

(deftest handlers-should-use-the-first-matching-clause
  (is (= :value
         (with-handlers [(ArithmeticException [ex]
                           :value)
                         (Exception [ex])]
           (/ 1 0))))
  (is (= nil
         (with-handlers [(Exception [ex])
                         (ArithmeticException [ex]
                           :value)]
           (/ 1 0)))))

(deftest handlers-should-work-even-above-try-catch
  (is (= 20
         (with-handlers [(Exception [ex]
                           (use-restart :use-value 10))]
           (try
             (+ (with-restarts [(:use-value [value]
                                  value)]
                  (throw (Exception.)))
                10)
             (catch Exception ex))))))

(deftest restarts-from-exceptions-should-work
  (is (= 10
         (with-handlers [(Exception [ex]
                           (use-restart :use-value 10))]
           (with-restarts [(:use-value [value] value)]
             (throw (RuntimeException.)))))))

(deftest restarts-should-bubble-up-if-unhandled
  (is (= 10
         (with-handlers [(Exception [ex] (use-restart :use-value 10))]
           (with-handlers [(ArithmeticException [ex])]
             (with-restarts [(:use-value [value] value)]
               (throw (RuntimeException.)))))))
  (is (= 10
         (with-handlers [(Exception [ex] (use-restart :use-value 10))]
           (with-handlers [(Exception [ex] (signal ex))]
             (with-restarts [(:use-value [value] value)]
               (throw (RuntimeException.))))))))

(deftest signal-should-pass-more-arguments
  (is (= 34
         (with-handlers [(Exception [ex & rest]
                           (apply + rest))]
           (signal (RuntimeException.) 10 20 4)))))

(deftest default-handler-should-only-be-called-once-when-aborting
  (let [num (atom 0)]
    (try (with-handlers [(RuntimeException [ex]
                           (swap! num inc)
                           (throw ex))]
           (throw (RuntimeException.)))
         (catch Throwable t))
    (is (= 1 @num)))
  (let [num (atom 0)]
    (try (with-handlers [(RuntimeException [ex]
                           (swap! num inc)
                           (throw ex))]
           (with-restarts [(:use-value [value] value)]
             (throw (RuntimeException.))))
         (catch Throwable t))
    (is (= 1 @num)))
  (let [num (atom 0)]
    (try (with-handlers [(RuntimeException [ex]
                           (swap! num inc)
                           (throw ex))]
           (with-restarts [(:use-value [value] value)]
             (with-restarts [(:use-value [value] value)]
               (throw (RuntimeException.)))))
         (catch Throwable t))
    (is (= 1 @num)))
  (let [num (atom 0)]
    (try (with-handlers [(RuntimeException [ex]
                           (swap! num inc)
                           (throw ex))]
           (with-handlers [(ArithmeticException [ex])]
             (with-handlers [(IllegalArgumentException [ex])]
               (throw (RuntimeException.)))))
         (catch Throwable t))
    (is (= 1 @num))))

(deftest handlers-returning-values-should-return-at-the-right-place
  (is (= 2
         (with-handlers [(RuntimeException [& args] 2)]
           (+ 1 (with-handlers [(ArithmeticException [ex])]
                  (throw (RuntimeException.))))))))

(deftest handlers-should-not-modify-exceptions-when-not-handling
  (binding [*default-handler* (fn [ex & args]
                                (throw ex))]
    (let [ex (Exception.)]
      (is (= ex
             (try
               (with-handlers [(ArithmeticException [ex] 10)]
                 (throw ex))
               (catch Exception e e))))
      (is (= ex
             (try
               (with-handlers [(ArithmeticException [ex] 10)]
                 (throw ex))
               (catch Exception e e)))))))

(deftest restarts-should-not-modify-exceptions-when-not-handling
  (binding [*default-handler* (fn [ex & args]
                                (throw ex))]
    (let [ex (Exception.)]
      (is (= ex
             (try
               (with-restarts [(:use-value [value] value)]
                 (throw ex))
               (catch Exception e e))))
      (is (= ex
             (try
               (with-restarts [(:use-value [value] value)]
                 (throw ex))
               (catch Exception e e)))))))

(deftest handlers-throwing-exceptions-should-be-catchable
  (binding [*default-handler* (fn [ex & args]
                                (throw ex))]
    (is (= Exception
           (try
             (with-handlers [(RuntimeException [ex] (throw (Exception.)))]
               (throw (RuntimeException.)))
             (catch Exception ex
               (.getClass ex)))))
    (is (= Exception
           (try
             (with-handlers [(RuntimeException [ex] (throw (Exception.)))]
               (throw (RuntimeException.)))
             (catch Exception ex
               (.getClass ex)))))
    (is (= Exception
           (try
             (with-handlers [(RuntimeException [ex] (throw (Exception.)))]
               (with-restarts [(:use-value [value] value)]
                 (throw (RuntimeException.))))
             (catch Exception ex
               (.getClass ex)))))
    (is (= Exception
           (try
             (with-handlers [(RuntimeException [ex] (throw (Exception.)))]
               (with-restarts [(:use-value [value] value)]
                 (throw (RuntimeException.))))
             (catch Exception ex
               (.getClass ex)))))))

(deftest restarts-should-go-away-during-handler
  (is (= 0
         (with-handlers [(Exception [ex]
                           (use-restart :try-1))]
           (with-restarts [(:try-1 []
                             (count *restarts*))]
             (throw (RuntimeException.)))))))
