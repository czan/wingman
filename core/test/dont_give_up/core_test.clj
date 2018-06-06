(ns dont-give-up.core-test
  (:require [clojure.test :refer :all]
            [dont-give-up.core :refer :all]))

(deftest make-restart-passes-restart?
  (is (restart? (make-restart "name" nil nil #(do)))))

(deftest restart?-fails-other-objects
  (is (not (restart? nil)))
  (is (not (restart? :key)))
  (is (not (restart? "str")))
  (is (not (restart? 10))))

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
