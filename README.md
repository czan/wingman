# dont-give-up

Oh no, something's gone wrong! Don't give up!

## Usage

```clojure
(defn div [n d]
  (with-restarts [(:use-denominator [value]
                    :arguments #'read-unevaluated-value
                    (div n value))
                  (:use-value [value]
                    :arguments #'read-unevaluated-value
                    value)]
    (/ n d)))

(with-handlers [(ArithmeticException [ex]
                  (use-restart :use-value 100))]
  (div 3 0)) ;; => 100

(with-handlers [(ArithmeticException [ex]
                  (use-restart :use-denominator 100))]
  (div 3 0)) ;; => 3/100

(div 3 0)
;; Error: Divide by zero
;;
;; These are the currently-available restarts:
;;   1 :use-denominator
;;   2 :use-value
;;   q Abort
;; Enter a restart to use: *1*
;; Enter a value to be used (unevaluated): *3*
;; => 3/4
```

## License

Copyright © 2018 Carlo Zancanaro

Distributed under the MIT License.
