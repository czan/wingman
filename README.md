# dont-give-up

Oh no, something's gone wrong! Don't give up! Restart your computation using Common Lisp-style restarts, instead!

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.czan/dont-give-up.svg)](https://clojars.org/org.clojars.czan/dont-give-up)

Restarts can be used in code, as shown below, or they can be used interactively. When an exception is thrown, the user is asked which restart to use (if there are any available). This has been implemented as nrepl middleware, with an associated cider extension.

`dont-give-up` has been designed to be as unobtrusive as possible. You can add it to your `~/.lein/profiles.clj` file to get the benefit of interactive restarts in CIDER without affecting anything else.

To use interactive restarts with CIDER, load `dont-give-up.el` from this repo in Emacs, and install the `dont-give-up.middleware/handle-restarts` middleware. You can do this by adding it to `~/.lein/profiles.clj`, like this:

```clojure
{:user {:dependencies [[org.clojars.czan/dont-give-up "0.1.0-SNAPSHOT"]]
        :repl-options {:nrepl-middleware [dont-give-up.middleware/handle-restarts]}}}
```

## Example

In a newly-started CIDER REPL with the `dont-give-up` middleware loaded, run:

```clojure
(inc x)
```

Your REPL should now freeze (because the evaluation is waiting), and a new buffer should pop up with the following:

```
Unable to resolve symbol: x in this context

The following restarts are available:
  [1] :retry Retry the REPL evaluation.
  [2] :define-and-retry Provide a value for `x` and retry the evaluation.
  [3] :refer-and-retry Provide a namespace to refer `x` from and retry the evaluation.
  [q] abort Abort this evaluation.


... Followed by exception details/stacktrace
```

You are being asked how to continue. As the message explains, you have four options:

1. Retry the evaluation again, exactly the same as it was. In this case it would just fail again, as `x` still hasn't been defined, but this can be helpful for some things which resolve themselves.

2. Retry the evaluation again, but after defining `x`. If you choose this option you will be prompted for a value to use. `x` will then be defined in the current namespace to the value you provided, and the evaluation will be run again.

3. Retry the evaluation again, but after referring the var from another namespace. If you choose this option you will be prompted for a namespace to refer. `x` will then be defined as if you had run `(require `[~provided-ns :refer [x]])`.

4. Abort. Just give up, and stop.

For this example, we'll select 2. Press the `2` key on your keyboard, and type the number `300` into the minibuffer prompt.

The REPL should now show the return value of the evaluation: `301`.

You can also evaluate `x` in the REPL to see that it is, in fact, `300`.

To show off another option, try running `(union #{1 2} #{2 3})`, and using the interactive restart to refer from `clojure.set`.

## Why restarts?

Why should we want to use restarts in Clojure? [Chris Houser already gave us a great model for error handling in Clojure](https://www.youtube.com/watch?v=zp0OEDcAro0), why should I use `dont-give-up`? The answer to this question is really about _interactivity_.

The method of binding dynamic variables for error handling is roughly equivalent to what `dont-give-up` does, but where the plain dynamic-variables approach fails is tool support. There is no way for a tool to find out what the options are to restart execution, and to present that choice to the user in an interactive session. From the start, the focus in `dont-give-up` has been on the REPL experience. It is primarily about recovering from errors in the REPL, and only then making that same functionality available in code.

## What about Exceptions?

Obviously, Clojure executes on a host which doesn't natively support restarts. As a result, restarts have been implemented using JVM Exceptions to manipulate the normal control flow of the program. There are a few edge-cases, but for the most past this should interoperate with native JVM Exceptions, allowing them to pass through uninterrupted if no handlers have been established. This means that adding restarts to a library should have no effect on a program unless it opts-in to using them by installing handlers.

There is the potential for a library/application to break `dont-give-up` by catching things that should be allowed through. All the internal types derive from Throwable, so as long as you don't catch Throwable you should be fine. If you do catch Throwable, please ensure that `dont_give_up.UseRestart` and `dont_give_up.HandlerResult` are re-thrown.

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

(with-handlers [(ArithmeticException ex
                  (use-restart :use-value 100))]
  (div 3 0)) ;; => 100

(with-handlers [(ArithmeticException ex
                  (use-restart :use-denominator 100))]
  (div 3 0)) ;; => 3/100
```

## License

Copyright © 2018 Carlo Zancanaro

Distributed under the MIT License.
