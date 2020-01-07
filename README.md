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
[fumi "0.1.0-SNAPSHOT"]
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

## Built-in collectors

By default, fumi comes with collectors that expose several useful metrics:

- JVM: memory, threads, garbage collection, jvm info
- process: cpu, memory, file descriptors

To exclude them, `init!` the registry with `:exclude-defaults?` set to true

## License

Released under the MIT License: http://www.opensource.org/licenses/mit-license.php
