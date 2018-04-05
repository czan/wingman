# dont-give-up

Oh no, something's gone wrong! Don't give up! Restart your computation using Common Lisp-style restarts, instead!

## Usage

```clojure
(require '[dont-give-up.core :refer (with-restarts with-handlers read-unevaluated-value use-restart)])

(defn div [n d]
  (with-restarts [(:use-value [value]
                    :arguments #'read-unevaluated-value
                    :describe "Provide a value to use."
                    value)
                  (:use-denominator [value]
                    :arguments #'read-unevaluated-value
                    :describe "Provide a new denominator and retry."
                    (div n value))]
    (/ n d)))

(with-handlers [(ArithmeticException [ex]
                  (use-restart :use-value 100))]
  (div 3 0)) ;; => 100

(with-handlers [(ArithmeticException [ex]
                  (use-restart :use-denominator 100))]
  (div 3 0)) ;; => 3/100
```

In the case of an unhandled error, the user is prompted how to proceed (user input is surrounded by `*`s):
```clojure
(div 3 0)
;; Error: Divide by zero
;; 
;; These are the currently-available restarts:
;;   [1] :use-value - Provide a value to use.
;;   [2] :use-denominator - Provide a new denominator and retry.
;;   [q] Abort - Rethrow the exception.
;; Enter a restart to use: *2*
;; Enter a value to be used (unevaluated): *2*
;; => 3/4
```

## License

Copyright © 2018 Carlo Zancanaro

Distributed under the MIT License.
