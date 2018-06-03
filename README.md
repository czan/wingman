# dont-give-up

Oh no, something's gone wrong! Don't give up! Restart your computation using Common Lisp-style restarts, instead!

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.czan/dont-give-up.svg)](https://clojars.org/org.clojars.czan/dont-give-up)

## Setup

To get the most out of `dont-give-up`, install the [CIDER support][1].

[1]: https://github.com/czan/dont-give-up.nrepl

## Usage

Register restarts with the `with-restarts` macro. This example wraps `inc` into a function which allows us to recover if we have accidentally passed it a non-number value.

```clojure
(require '[dont-give-up.core :refer [with-restarts with-handlers invoke-restart]])
(defn restartable-inc [x]
  (with-restarts [(:use-value [value] value)]
    (inc x)))
;;=> #'user/restartable-inc
```

Now, we can map this function over a list with some non-number values:

```clojure
(into [] (map restartable-inc [1 2 3 :a :b nil]))
;;=> ClassCastException: clojure.lang.Keyword cannot be cast to java.lang.Number
```

Note that the behaviour of the function is unchanged when there is no appropriate handler established. Adding restarts does nothing if there aren't any appropriate handlers registered. However, if we wrap it in a `with-handlers` form:

```clojure
(with-handlers [(Exception ex (invoke-restart :use-value nil))]
  (into [] (map restartable-inc [1 2 3 :a :b nil 10 11 12])))
;;=> [2 3 4 nil nil nil 11 12 13]
```

When an error is encountered, the handler provided by `with-handlers` is called to decide on a course of action. In this case, it always decides to invoke the `:use-value` restart with a value of `nil`. This results in each of the error cases being added into the list as a `nil`.

It is also possible to have multiple layers of restarts to choose from. For example, we might define our own `restartable-map`, which lets us skip items that throw exceptions:

```clojure
(defn restartable-map [f s]
  (lazy-seq
    (when (seq s)
      (with-restarts [(:skip [] (restartable-map f (rest s)))]
        (cons (f (first s)) (restartable-map f (rest s)))))))
;;=> #'user/restartable-map
```

Now we can run the same example as before:

```clojure
(with-handlers [(Exception ex (invoke-restart :use-value nil))]
  (into [] (restartable-map restartable-inc [1 2 3 :a :b nil 10 11 12])))
;;=> [2 3 4 nil nil nil 11 12 13]
```

Or, we can change our strategy and decide to skip failing values:

```clojure
(with-handlers [(Exception ex (invoke-restart :skip))]
  (into [] (restartable-map restartable-inc [1 2 3 :a :b nil 10 11 12])))
;;=> [2 3 4 11 12 13]
```

Or we can decide that we want to replace `nil` with `0`, and skip everything else:

```clojure
(with-handlers [(NullPointerException ex (invoke-restart :use-value 0))
                (Exception ex (invoke-restart :skip))]
  (into [] (restartable-map restartable-inc [1 2 3 :a :b nil 10 11 12])))
;;=> [2 3 4 11 12 13]
```

In this way, restarts allow us to separate the _decision_ about how to recover from an error from the _mechanics_ of actually recovering from the error. This enables higher-level code to make decisions about how lower level functions should recover from their errors, without unwinding the stack.

## Why restarts?

Why should we want to use restarts in Clojure? [Chris Houser already gave us a great model for error handling in Clojure](https://www.youtube.com/watch?v=zp0OEDcAro0), why should I use `dont-give-up`? The answer to this question is really about _interactivity_.

The method of binding dynamic variables for error handling is roughly equivalent to what `dont-give-up` does, but where the plain dynamic-variables approach fails is tool support. There is no way for our tooling to find out what the options are to restart execution, and to present that choice to the user in an interactive session. From the start, the focus in `dont-give-up` has been on the REPL experience. It is primarily about recovering from errors in the REPL, and only then making that same functionality available in code.

## What about Exceptions?

Obviously, Clojure executes on a host which doesn't natively support restarts. As a result, restarts have been implemented using JVM Exceptions to manipulate the normal control flow of the program. There are a few edge-cases, but for the most past this should interoperate with native JVM Exceptions, allowing them to pass through uninterrupted if no handlers have been established. This means that adding restarts to a library should have _no effect_ on a program unless that program opts-in to using them by installing handlers.

There is the potential for a library/application to break `dont-give-up` by catching things that should be allowed through. All the internal types derive from `java.lang.Throwable`, so as long as you don't catch `Throwable` you should be fine. If you do catch `Throwable`, please ensure that `dont_give_up.core.UseRestart`, `dont_give_up.core.HandlerResult`, `dont_give_up.core.UnhandledException` are re-thrown.

## Writing restarts

Restarts allow a piece of code to specify reasonable strategies to deal with errors that occur within them. They may allow you to simply use a specified value, or they may allow you to do complex actions like restart an agent, or reconnect a socket.

As an example, a simple restart to use a provided value would look like this:

```clojure
(with-restarts [(:use-value [value] value)]
  (/ 1 0))
```

This would allow a handler to invoke `(invoke-restart :use-value 10)` to recover from this exception, and to return `10` as the result of the `with-restarts` form.

In addition, restarts can have three extra attributes defined:

1. `:applicable?` specifies a predicate which tests whether this restart is applicable to this exception type. It defaults to `(constantly true)`, under the assumption that restarts are always applicable.

2. `:describe` specifies a function which will convert the exception into an explanation of what this restart will do. As a shortcut, you may use a string literal instead, which will be converted into a function returning that string. It defaults to `(constantly "")`.

3. `:arguments` specifies a function which will return arguments for this restart. This function is only ever used interactively, and thus should prompt the user for any necessary information to invoke this restart. It defaults to `(constantly nil)`.

Here is an example of the above restart using these attributes:

```clojure
(with-restarts [(:use-value [value]
                   :describe "Provide a value to use."
                   :arguments #'read-unevaluated-value
                   value)]
  (/ 1 0))
```

Restarts are invoked in the same dynamic context in which they were defined. The stack is unwound to the level of the `with-restarts` form, and the restart is invoked.

Multiple restarts with the same name can be defined, but the "closest" one will be invoked by a call to `invoke-restart`.

Restart names can be any value that is not an instance of `dont-give-up.core.Restart`, but it is recommended to use keywords as names.

## Writing handlers

Handlers are conceptually similar to try/catch, but they are invoked without unwinding the stack. This gives them greater scope to make decisions about how to recover from errors. Ultimately, though, they can only recover in ways that have registered restarts.

For example, here is how to use `with-handlers` to replace try/catch:

```clojure
(with-handlers [(Exception ex (.getMessage ex))]
  (/ 1 0))
;;=> "Divide by zero"
```

Similarly to try/catch, multiple handlers can be defined for different exception types, and the first matching handler will be run to handle the exception.

Handlers can have only one of five outcomes:

1. invoke `invoke-restart`, which will restart execution from the specified restart

2. invoke `rethrow`, which will defer to a handler higher up the call-stack, or `throw` if this is the highest handler

3. return a value, which will be the value returned from the `with-handler-fn` form

4. throw an exception, which will be thrown as the result of the `with-handler-fn` form

5. invoke `unhandle-exception`, which will re-throw the exception from where it was caught

Conceptually, options `1` and `2` process the error without unwinding the stack, and options `3` and `4` unwind the stack up until the handler.

Option `5` is a special case, and will propagate the exception as if `dont-give-up` had never caught it. This can have some surprising effects, and should only be used in cases where the exception is required be propagated through normal JVM stack unwinding. The most common reason for this is for code which relies on exceptions to do feature detection. Normally `dont-give-up` could bypass those catch clauses, so the exception must be left unhandled.

## License

Copyright © 2018 Carlo Zancanaro

Distributed under the MIT License.
