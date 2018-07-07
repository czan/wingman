(ns wingman.core-test
  (:require [clojure.test :refer [deftest is #?@(:clj [use-fixtures])]]
            [wingman.core :as sut]
            #?@(:cljs [[doo.runner :refer-macros [doo-tests]]])))

#?(:cljs
   (def NullPointerException js/TypeError))
#?(:cljs
   (def Exception js/Error))

(defn throw-exception []
  (let [x nil]
    (x)))

#?(:clj
   (use-fixtures :once (fn [f]
                         (sut/without-handling
                          (f)))))

(deftest handlers-should-use-the-first-matching-clause
  (is (= :value
         (sut/with-handlers [(NullPointerException ex
                               :value)
                             (Exception ex)]
           (throw-exception))))
  (is (= nil
         (sut/with-handlers [(Exception ex)
                             (NullPointerException ex
                               :value)]
           (throw-exception)))))

(deftest handlers-should-work-even-above-try-catch
  (is (= 11
         (sut/with-handlers [(Exception, ex
                               (sut/invoke-restart :use-value 10))]
           (try
             (+ (sut/with-restarts [(:use-value [value]
                                      value)]
                  (throw-exception))
                1)
             (catch Exception ex))))))

(deftest restarts-from-exceptions-should-work
  (is (= 10
         (sut/with-handlers [(Exception ex
                               (sut/invoke-restart :use-value 10))]
           (sut/with-restarts [(:use-value [value] value)]
             (throw-exception))))))

(deftest restarts-should-bubble-up-if-unhandled
  (is (= 10
         (sut/with-handlers [(Exception ex
                               (sut/invoke-restart :use-value 10))]
           (sut/with-handlers [(NullPointerException ex)]
             (sut/with-restarts [(:use-value [value] value)]
               (throw (Exception.)))))))
  (is (= 10
         (sut/with-handlers [(Exception ex
                               (sut/invoke-restart :use-value 10))]
           (sut/with-handlers [(NullPointerException ex
                                 (sut/rethrow ex))]
             (sut/with-restarts [(:use-value [value] value)]
               (throw (Exception.))))))))

(deftest restarts-should-use-the-most-specific-named-restart
  (is (= 10
         (sut/with-handlers [(Exception ex (sut/invoke-restart :use-default))]
           (sut/with-restarts [(:use-default [] 13)]
             (sut/with-restarts [(:use-default [] 10)]
               (throw-exception)))))))

(deftest restarts-should-restart-from-the-right-point
  (is (= 0
         (sut/with-handlers [(Exception ex (sut/invoke-restart :use-zero))]
           (sut/with-restarts [(:use-zero [] 0)]
             (inc (sut/with-restarts [(:use-one [] 1)]
                    (throw-exception))))))))

(deftest handlers-returning-values-should-return-at-the-right-place
  (is (= 2
         (sut/with-handlers [(Exception ex 2)]
           (+ 300 (sut/with-handlers [(NullPointerException ex)]
                    (throw (Exception.))))))))

(deftest handlers-should-not-modify-exceptions-when-not-handling
  (let [ex (Exception.)]
    (is (= ex
           (try
             (sut/with-handlers [(NullPointerException ex 10)]
               (throw ex))
             (catch Exception e e))))
    (is (= ex
           (try
             (sut/with-handlers [(NullPointerException ex 10)]
               (throw ex))
             (catch Exception e e))))))

(deftest restarts-should-not-modify-exceptions-when-not-handling
  (let [ex (Exception.)]
    (is (= ex
           (try
             (sut/with-restarts [(:use-value [value] value)]
               (throw ex))
             (catch Exception e e))))
    (is (= ex
           (try
             (sut/with-restarts [(:use-value [value] value)]
               (sut/with-restarts [(:use-value [value] value)]
                 (throw ex)))
             (catch Exception e e))))))

(deftest handlers-throwing-exceptions-should-be-catchable
  (is (= Exception
         (try
           (sut/with-handlers [(NullPointerException ex (throw (Exception.)))]
             (throw-exception))
           (catch Exception ex
             (type ex)))))
  (is (= Exception
         (try
           (sut/with-handlers [(NullPointerException ex (throw (Exception.)))]
             (sut/with-restarts [(:use-value [value] value)]
               (throw-exception)))
           (catch Exception ex
             (type ex)))))
  (is (= Exception
         (sut/with-handlers [(Exception ex 10)]
           (try
             (sut/with-handlers [(NullPointerException ex (throw (Exception.)))]
               (sut/with-restarts [(:use-value [value] value)]
                 (throw-exception)))
             (catch Exception ex
               (type ex)))))))

(deftest restarts-should-go-away-during-handler
  (is (= 0
         (sut/with-handlers [(Exception ex
                               (sut/invoke-restart :try-1))]
           (sut/with-restarts [(:try-1 []
                                 (count (sut/list-restarts)))]
             (throw-exception))))))

#?(:cljs
   (doo-tests 'wingman.core-test))
