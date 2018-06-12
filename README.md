# wingman [![wingman](https://img.shields.io/clojars/v/wingman.svg)](https://clojars.org/wingman) [![wingman/base](https://img.shields.io/clojars/v/wingman/base.svg)](https://clojars.org/wingman/base) [![wingman/wingman.nrepl](https://img.shields.io/clojars/v/wingman/wingman.nrepl.svg)](https://clojars.org/wingman/wingman.nrepl)

Restartable exception handling for Clojure, allowing you to recover from exceptions without unwinding the stack.

`wingman` tries hard to interoperate with the existing JVM exception system, to enable code using restarts to easily interoperate with code that uses plain exceptions. Libraries writers can add restarts, and applications can ignore them (using try/catch as usual), or use them (by registering a handler and invoking restarts) as they see fit. Adding restarts to a library does not _require_ applications to change their exception handling strategy, but it will provide them with more options for how to deal with errors.

## Setup

Add `[wingman "0.3.0"]` to your dependency vector.

To get the most out of `wingman`, install the CIDER support by loading `cider-wingman.el` in Emacs.

## Usage

Register restarts with the `with-restarts` macro. This example wraps `inc` into a function which allows us to recover if we have accidentally passed it a non-number value.

```clojure
(require '[wingman.core :refer [with-restarts with-handlers invoke-restart]])
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

Why should we want to use restarts in Clojure? [Chris Houser already gave us a great model for error handling in Clojure](https://www.youtube.com/watch?v=zp0OEDcAro0), why should I use `wingman`? The answer to this question is really about _interactivity_.

The method of binding dynamic variables for error handling is roughly equivalent to what `wingman` does, but where the plain dynamic-variables approach fails is tool support. There is no way for our tooling to find out what the options are to restart execution, and to present that choice to the user in an interactive session. From the start, the focus in `wingman` has been on the REPL experience. It is primarily about recovering from errors in the REPL, and only then making that same functionality available in code.

## What about Exceptions?

Obviously, Clojure executes on a host which doesn't natively support restarts. As a result, restarts have been implemented using JVM Exceptions to manipulate the normal control flow of the program. There are a few edge-cases, but for the most part this should interoperate with native JVM Exceptions, allowing them to pass through uninterrupted if no handlers have been established. This means that adding restarts to a library should have _no effect_ on a program unless that program opts-in to using them by installing handlers.

There is the potential for a library/application to break `wingman` by catching things that should be allowed through. All the internal types derive from `java.lang.Throwable`, so as long as you don't catch `Throwable` you should be fine. If you do catch `Throwable`, please ensure that `wingman.base.ScopeResult` is re-thrown.

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

Multiple restarts with the same name can be defined, but the "deepest" one will be invoked by a call to `invoke-restart`. You can use `find-restarts`, or even `list-restarts`, if you would like to introspect the available restarts.

Restart names can be any value that is not an instance of `wingman.base.Restart`, but it is recommended to use keywords as names.

## Writing handlers

Handlers are conceptually similar to try/catch, but they are invoked without unwinding the stack. This gives them greater scope to make decisions about how to recover from errors. Ultimately, though, they can only recover in ways that have registered restarts.

For example, here is how to use `with-handlers` to replace try/catch:

```clojure
(with-handlers [(Exception ex (.getMessage ex))]
  (/ 1 0))
;;=> "Divide by zero"
```

Similarly to try/catch, multiple handlers can be defined for different exception types, and the first matching handler will be run to handle the exception.

There are five possible outcomes for a handler:

1. Return a value normally: the `call-with-handler` form will return that value.

2. Throw an exception: the `call-with-handler` form will throw that exception.

3. Invoke a restart, using `invoke-restart`: the handler will cease executing, and the code of the restart will be invoked, continuing to execute from that point.

4. Invoke `unhandle-exception` on an exception: the exception will be thrown from the point where this handler was invoked. This should be used with care.

5. Invoke `rethrow` on an exception: defer the decision to a handler higher in the call-stack. If there is no such handler, the exception will be thrown (which will appear the same as option 2).

Conceptually, options 1 and 2 unwind the stack up until the handler, option 3 unwinds the stack to the appropriate restart, option 4 hands back to JVM exception handling, and option 5 delegates to another handler without unwinding the stack at all.

Invoking `unhandle-exception` is primarily useful when working with code that uses exceptions to provide fallback behaviour. The restart handler mechanism can, in some cases, cause catch clauses to be \"skipped\", bypassing exception-based mechanisms. If possible, avoid using `unhandle-exception`, as it can result in restart handlers firing multiple times for the same exception.

## License

Copyright © 2018 Carlo Zancanaro

Distributed under the MIT License.
