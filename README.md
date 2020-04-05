# fumi

[![Clojars Project](https://img.shields.io/clojars/v/fumi.svg)](https://clojars.org/fumi)


A Prometheus client for Clojure.

Features:

- Pure Clojure with no dependencies
- Exports built-in JVM/process metrics
- Supports all prometheus metric types (counter, gauge, summary, histogram)
- Supports central configuration as well as creating collectors next to the code they instrument.
- Supports custom collectors implemented as functions (implementing a protocol)

## Status

fumi is under active develpment and may experience breaking changes. 
Please try it out and raise any issues you may find.

## Usage

For Leinengen, add this to your project.clj:

```clojure
[fumi "0.1.0-beta1"]
```

## Getting started

```clojure 
;; A default registry is already defined and exports jvm and process metrics. 
;; Use init! to redefine it, if you want to centralise configuration.
(init!
  {:exclude-defaults? false
   :collectors        {:test_counter   {:type :counter :help "a counter" :label-names [:foo]}
                       :test_gauge     {:type :gauge :help "a gauge"}
                       :test_histogram {:type :histogram :help "a histogram"}}})

;; Alternatively add collectors to the existing registry, useful
;; if you want to configure them next to the code they instrument
(register! :another_counter {:type :counter :help "another counter"})
(register! :test_summary {:type :summary :help "a summary"})

;; Observe some values
(increase! :test_counter {:n 3 :labels {:foo "bar"}})
(set-n! :test_gauge 2.1 {})
(observe! :test_histogram 0.51 {})

;; Output
(-> (collect)
    (serialize :text))
```

See the section on using self managed registries for other usage patterns.

## Using other registries

Typically using the default registry is sufficient, and handles hundreds of thousands of updates per second.
fumi also allows you to use separate registries or hold onto references (e.g. to inject via component-style systems).
In this case, the registry (i.e. result of calling `init!`) must be passed in as the `:registry` option
to all subsequent operations.

```clojure 
;; Create your registry by using the :self-managed? argument and holding on to the result
(def my-registry 
  (init! {:self-managed?     true
          :exclude-defaults? true
          :collectors        {:test_counter {:type :counter :help "a counter" :label-names [:foo]}}}))

;; Add more metrics to it
(register! :another_counter {:type :counter :help "another counter" :registry my-registry})

;; Observe some values
(increase! :test_counter {:n 3 :labels {:foo "bar"} :registry my-registry})

;; Output -- explicitly include default-registry if you want it
(-> (collect my-registry default-registry)
    (serialize :text))
```

## Metric types

fumi supports the standard prometheus [metric types](https://prometheus.io/docs/concepts/metric_types/)
(counter, gauge, summary, histogram). They are defined through a data driven API either during
initial setup of a registry via `init!`, or later on via `register!`.
 
In either case, the metric must be identified by a keyword (e.g. `:my_counter`), and takes these options:
- `:type` is a keyword corresponding to one of the metric types (`:counter`, `:gauge`, `:summary`, `:histogram`)
- `:help` is a short string description
- `:label-names` (optional) is a list of keywords. If supplied, any operation on the metric needs a 
  corresponding `:labels` map providing a value for each label-name

Histograms takes a further option:
- `:buckets` (optional), defaulting to `[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]`

### Operations

These are documented in more details in the [API docs](https://cljdoc.org/d/fumi/fumi/CURRENT/api/fumi.client).

#### Counter

A counter can only increase.

```clojure
(increase! :my_counter)
(increase! :another_counter {:n 3 :labels {:uri "/home"}})
```

#### Gauge

A gauge can go up or down, or be set to a specific number.

```clojure
(increase! :my_gauge)
(decrease! :my_gauge {:n 2})
(set-n! :my_gauge 3.2)
```

#### Summary and Histogram

These metric types create a distribution based on observations. 

```clojure
(observe! :my_summary 3.2)
(observe! :my_histogram 4.7)
```

In all the above, if a metric was defined with one or more `:label-names`, an operation has to provide 
a `:labels` map with an entry for every label name.

## Built-in collectors

By default, fumi comes with collectors that expose several useful metrics:

- JVM: memory, threads, garbage collection, jvm info
- process: cpu, memory, file descriptors

To exclude them, `init!` the registry with `:exclude-defaults?` set to true

## Custom collectors

On top of creating the standard metric collectors and performing operations on them based on some event,
it is possible to directly implement `Collectable`, providing a function that returns the necessary data,
either as a single map, or a collection of them. 

```clojure
; Add a collector that a counter that is always stuck on 42.
(fc/register! :constant_counter 
              (reify fumi.collector/Collectable
                     (-collect [_] {:help    "help"
                                    :name    :constant_counter
                                    :samples [{:value 42}]
                                    :type    :counter})))
```

See the jvm/process collectors for further examples.

## Generating output

Use `(collect)` to get the state of a registry at a point in time.

To satisfy the `/metrics` endpoint that a Prometheus server expects, create a route that returns
the data in text format:

```clojure
(-> (collect) 
    (serialize :text)) 

; Returns lines of text like:
; # HELP process_cpu_seconds_total Total user and system CPU time spent in seconds.
; # HELP process_cpu_seconds_total counter
; process_cpu_seconds_total 18.976212
```

Note that `(collect)` with no arguments will collect from the default-registry, but if passed any arguments,
the default-registry needs to be explicitly provided:

```clojure
(collect my-registry default-registry)
```

## License

Released under the MIT License: http://www.opensource.org/licenses/mit-license.php
