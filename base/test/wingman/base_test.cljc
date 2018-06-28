(ns wingman.base-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [wingman.base :as sut]
            #?@(:cljs [[doo.runner :refer-macros [doo-tests]]])))

(defn throw-exception []
  #?(:clj (/ 1 0)
     :cljs (throw (js/Error. "Divide by zero"))))

;; Handlers have five basic behaviours that we want to test:

;; 1. The handler's return value is used as the value of the
;; `call-with-handler` form.

(deftest handler-return-values-are-used-directly
  (is (= (sut/call-with-handler (fn [ex] :a)
           #(throw-exception))
         :a))
  (is (= (sut/call-with-handler (fn [ex] :b)
           #(sut/call-with-restarts (fn [ex])
              (fn [] (throw-exception))))
         :b))
  (is (= (sut/call-with-handler (fn [ex] :c)
           #(+ (throw-exception)
               3))
         :c)))

;; 2. If the handler throws an exception, it is propagated as the
;; result of the `call-with-handler` form.

(deftest handler-thrown-exceptions-are-thrown
  (is (thrown-with-msg?
       #?(:clj Exception
          :cljs js/Error)
       #"THIS IS A TEST EXCEPTION"
       (sut/call-with-handler (fn [ex] (throw (#?(:clj Exception.
                                                 :cljs js/Error.)
                                              "THIS IS A TEST EXCEPTION")))
         #(throw-exception))))
  (is (thrown-with-msg?
       #?(:clj Exception
          :cljs js/Error)
       #"THIS IS A TEST EXCEPTION"
       (sut/call-with-handler (fn [ex] (throw (#?(:clj Exception.
                                                 :cljs js/Error.)
                                              "THIS IS A TEST EXCEPTION")))
         #(sut/call-with-restarts (fn [ex])
            (fn [] (throw-exception)))))))

;; 3. If the handler invokes a restart, the computation continues from
;; the point of that `call-with-restarts` form.

(deftest handler-invoking-restart-restarts-computation
  (is (= (sut/call-with-handler (fn [ex]
                                  (let [[r] (sut/list-current-restarts)]
                                    (sut/invoke-current-restart r)))
           #(sut/call-with-restarts (fn [ex]
                                      [(sut/make-restart :use-a "" (constantly nil) (constantly :a))])
              (fn []
                (throw-exception))))
         :a))
  (is (= (sut/call-with-handler (fn [ex]
                                  (let [[r] (sut/list-current-restarts)]
                                    (sut/invoke-current-restart r :b :c)))
           #(sut/call-with-restarts (fn [ex]
                                      [(sut/make-restart :use-a "" (constantly nil) (fn [x y] [x y]))])
              (fn []
                (throw-exception))))
         [:b :c])))

;; 4. If the handler invokes `unhandle-exception`, an exception is
;; thrown from the point where it was first seen.

(deftest handler-unhandling-exception-throws
  ;; Note that in this case the exception never actually enters our
  ;; framework, because it is caught and dealt with before it
  ;; propagates high enough.
  (is (= (sut/call-with-handler (fn [ex] (sut/unhandle-exception ex))
           (fn []
             (try
               (throw-exception)
               (catch #?(:clj Exception :cljs :default) ex
                 :a))))
         :a))
  (is (= (sut/call-with-handler (fn [ex] (sut/unhandle-exception ex))
           #(try
              (sut/call-with-restarts (fn [ex] [])
                (fn []
                  (throw-exception)))
              (catch #?(:clj Exception :cljs :default) ex
                :b)))
         :b)))

;; 5. If the handler can't handle something, it `rethrow`s it to a
;; higher handler to make a decision (which might then do any of the
;; above).

(deftest handler-rethrowing-defers-to-higher-handler
  ;; This is just a sanity-check that the handlers get called in the
  ;; right order.
  (is (= (sut/call-with-handler (fn [ex] :a)
           #(sut/call-with-handler (fn [ex] :b)
              (fn [] (throw-exception))))
         :b))
  (is (= (sut/call-with-handler (fn [ex] :a)
           #(sut/call-with-handler (fn [ex] (sut/rethrow ex))
              (fn [] (throw-exception))))
         :a))
  (is (thrown-with-msg?
       #?(:clj ArithmeticException
          :cljs js/Error)
       #"Divide by zero"
       (sut/call-with-handler (fn [ex] (sut/rethrow ex))
         #(throw-exception)))))

;; And some other miscellaneous tests.

(deftest list-current-restarts-returns-empty-outside-of-handlers
  (sut/call-with-restarts (fn [ex]
                            [(sut/make-restart :r1 nil nil (constantly nil))])
    #(is (= (map :name (sut/list-current-restarts))
            []))))

(deftest list-current-restarts-returns-current-restarts-inside-handlers
  (sut/call-with-handler (fn [ex]
                           (is (= (map :name (sut/list-current-restarts))
                                  [:r1])))
    #(sut/call-with-restarts (fn [ex]
                               [(sut/make-restart :r1 nil nil (constantly nil))])
       (fn []
         (throw-exception)))))

(deftest rethrow-throws-exceptions
  (is (thrown? #?(:clj Exception
                  :cljs js/Error)
               (sut/rethrow #?(:clj (Exception.)
                               :cljs (js/Error.)))))
  (is (thrown? #?(:clj Exception
                  :cljs js/Error)
               (sut/call-with-handler (fn [ex]
                                        (sut/rethrow ex))
                 #(throw #?(:clj (Exception.)
                            :cljs (js/Error.))))))
  (is (thrown? #?(:clj Exception
                  :cljs js/Error)
               (sut/call-with-handler (fn [ex]
                                        (sut/rethrow ex))
                 #(sut/call-with-restarts (fn [ex] [])
                    (fn []
                      (throw #?(:clj (Exception.)
                                :cljs (js/Error.)))))))))

#?(:clj
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
               (recur :text to-eval))))))))

#?(:clj
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
               (remove-ns sym))))))))

#?(:clj
   (deftest docstring-examples
     (doseq [v (vals (ns-publics 'wingman.base))]
       (check-docstring-examples v))))

#?(:cljs
   (doo-tests 'wingman.base-test))
