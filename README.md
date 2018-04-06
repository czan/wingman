# dont-give-up

Oh no, something's gone wrong! Don't give up! Restart your computation using Common Lisp-style restarts, instead!

Restarts can be used in code, as shown below, or they can be used interactively. When an exception is thrown, the user is asked which restart to use (if there are any available). This has been implemented as nrepl middleware, with an associated cider extension.

To run interactively, load `dont-give-up.el`, and install the `dont-give-up.middleware/handle-restarts` middleware.

## Usage (interactive)

Add `:repl-options {:nrepl-middleware [dont-give-up.middleware/handle-restarts]}q to your `project.clj`.

Download `dont-give-up.el` from this repository and run `(load "/path/to/dont-live-up.el")`.

Now, when an exception is thrown, if there are restarts available you will be prompted for which one to use:

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

(div 3 0)
;; ArithmeticException: Divide by zero
;; 
;; The following restarts are available:
;;   [1] use-value - Provide a value to use instead.
;;   [2] use-denominator - Provide a value to use as the denominator.
;;   [q] abort - Rethrow the exception.
;; 
;; ----------------------
;; java.lang.ArithmeticException: Divide by zero
;; 	at clojure.lang.Numbers.divide(Numbers.java:158)
;;  ...
```


## Usage (code)

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

## License

Copyright © 2018 Carlo Zancanaro

Distributed under the MIT License.
