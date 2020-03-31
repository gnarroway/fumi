# fumi

[![Clojars Project](https://img.shields.io/clojars/v/fumi.svg)](https://clojars.org/fumi)


A Prometheus client for Clojure.

Features:

- Pure Clojure with no dependencies
- Exports built-in JVM/process metrics
- Supports all prometheus types (counter, gauge, summary, histogram)
- Supports central configuration as well as creating collectors next to the code they instrument.

## Status

fumi is under active develpment and may experience breaking changes. 
Please try it out and raise any issues you may find.

## Usage

For Leinengen, add this to your project.clj:

```clojure
[fumi "0.1.0-alpha"]
```

## Getting started

```clojure 
;; A default registry is already defined and exports jvm and process metrics. 
;; Use init! to redefine it, if you want to centralise configuration.
(init!
  {:exclude-defaults? false
   :collectors        {:test_counter   {:type :counter :description "a counter" :label-names [:foo]}
                       :test_gauge     {:type :gauge :description "a gauge"}
                       :test_histogram {:type :histogram :description "a histogram"}}})

;; Alternatively add collectors to the existing registry, useful
;; if you want to configure them next to the code they instrument
(register! :another_counter {:type :counter :description "another counter"})
(register! :test_summary {:type :summary :description "a summary"})

;; Observe some values
(increase! :test_counter {:n 3 :labels {:foo "bar"}})
(set-n! :test_gauge 2.1 {})
(observe! :test_histogram 0.51 {})

;; Output
(-> (collect)
    (serialize :text))
```

Typically using the default registry is sufficient, and handles hundreds of thousands of updates per second.
fumi also allows you to use separate registries or hold onto references (e.g. to inject via component-style systems).


```clojure 
;; Create your registry by using the :name argument and holding on to the result
(def my-registry 
  (init! {:name "my-registry"
          :exclude-defaults? true
          :collectors        {:test_counter   {:type :counter :description "a counter" :label-names [:foo]}}}))

;; Add more metrics to it
(register! :another_counter {:type :counter :description "another counter" :registry my-registry})

;; Observe some values
(increase! :test_counter {:n 3 :labels {:foo "bar"} :registry my-registry})

;; Output
(-> (collect my-registry)
    (serialize :text))
```

## Metric types

fumi supports the standard prometheus [metric types](https://prometheus.io/docs/concepts/metric_types/)
(counter, gauge, summary, histogram). They are defined through a data driven API either during
initial setup of a registry via `init!`, or later on via `register!`.
 
In either case, the metric must be identified by a keyword (e.g. `:my-counter`), and takes these options:
- `:type` is a keyword corresponding to one of the metric types (`:counter`, `:gauge`, `:summary`, `:histogram`)
- `:description` is a short string description
- `:label-names` (optional) is a list of keywords. If supplied, any operation on the metric needs a 
  corresponding `:labels` map providing a value for each label-name
  
Histograms takes a further option:
- `:buckets` (optional), defaulting to `[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]`

### Operations

These are documented in more details in the [API docs](https://cljdoc.org/d/fumi/fumi/0.1.0-alpha/api/fumi.client).

#### Counter

A counter can only increase.

```clojure
(increase! :my-counter)
(increase! :another-counter {:n 3 :labels {:uri "/home"}})
```

#### Gauge

A gauge can go up or down, or be set to a specific number.

```clojure
(increase! :my-gauge)
(decrease! :my-gauge {:n 2})
(set-n! :my-gauge 3.2)
```

#### Summary and Histogram

These metric types create a distribution based on observations. 

```clojure
(observe! :my-summary 3.2)
(observe! :my-histogram 4.7)
```

## Built-in collectors

By default, fumi comes with collectors that expose several useful metrics:

- JVM: memory, threads, garbage collection, jvm info
- process: cpu, memory, file descriptors

To exclude them, `init!` the registry with `:exclude-defaults?` set to true

## License

Released under the MIT License: http://www.opensource.org/licenses/mit-license.php
