# fumi

[![Clojars Project](https://img.shields.io/clojars/v/fumi.svg)](https://clojars.org/fumi)


A Prometheus client for Clojure.

Features:

- Simple wrapper of the official [java client](https://github.com/prometheus/client_java)
- Supports all prometheus metric types (counter, gauge, summary, histogram)
- Built in exporters for JVM / process metrics (additional dep required)
- Supports central configuration as well as creating collectors next to the code they instrument.
- Supports custom collectors implemented as functions

## Status

fumi is under active development and may experience breaking changes. 
Please try it out and raise any issues you may find.

## Usage

For Leinengen, add this to your project.clj:

```clojure
[fumi "0.3.0"]
```

## Getting started

```clojure 
;; A default registry is already defined and exports jvm and process metrics. 
;; Use init! to redefine it, if you want to centralise configuration.
(init!
  {:default-exports?  true
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

See the section on using self-managed registries for other usage patterns.

## Using other registries

Typically using the default registry is sufficient.
fumi also allows you to use separate registries or hold onto references. This may be useful for:

- injecting via component-style systems
- for using with push gateway
- test isolation

In this case, the registry (i.e. result of calling `init!`) must be passed in as the `:registry` option
to all subsequent operations.

```clojure 
;; Create your registry by using the :self-managed? argument and holding on to the result
(def my-registry 
  (init! {:self-managed?     true
          :default-exports?  false
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


## Generating output

Use `(collect)` to get the state of a registry at a point in time.

To satisfy the `/metrics` endpoint that a Prometheus server expects, create a route that returns
the data in text format:

```clojure
(-> (collect) 
    (serialize :text)) 

; Returns lines of text like:
; # HELP process_cpu_seconds_total Total user and system CPU time spent in seconds.
; # TYPE process_cpu_seconds_total counter
; process_cpu_seconds_total 18.976212
```

Note that `(collect)` with no arguments will collect from the default-registry, but if passed any arguments,
the default-registry needs to be explicitly provided:

```clojure
(collect my-registry default-registry)
```

## Built-in collectors

By default, fumi can expose [default collectors](https://github.com/prometheus/client_java#included-collectors) 
that expose several useful metrics:

- JVM: memory, threads, garbage collection, jvm info
- process: cpu, memory, file descriptors

To enable them, ensure you have the extra dependency:

```clojure
io.prometheus/simpleclient_hotspot {:mvn/version "0.12.0"}
```

`init!` the registry with `:default-exports?` set to true to add all of them.

To include them selectively, choose the relevant ones from those
[available](https://github.com/prometheus/client_java/blob/master/simpleclient_hotspot/src/main/java/io/prometheus/client/hotspot/DefaultExports.java#L37),
and provide them during `init!` or `register!`:

```clojure
; Add all default exports
(init! {:default-exports? true})

; Add only specific default exporters during init
(init! {:collectors {:version-info (io.prometheus.client.hotspot.VersionInfoExports.)}})

; Or to an existing registry
(register! :version-info (io.prometheus.client.hotspot.VersionInfoExports.))
```

## Custom collectors

On top of creating the standard metric collectors and performing operations on them based on some event,
it is possible to directly implement `Collector` abstract class, providing a function that returns the necessary data
as collection of metrics. You can directly pass in a function to `register!` or `init!` in place of an options map: 

```clojure
; Add a collector that a counter that is always stuck on 42.
(register! :constant_counter
           (fn [] [{:name    "metric"
                    :type    :counter
                    :help    "a constant counter"
                    :samples [{:value 42}]}
                   {:name    "metric_with_labels",
                    :type    :gauge,
                    :help    "a constant gauge with labels",
                    :samples [{:name   "active_requests",
                               :value  42
                               :labels {:source "foo"}}]}]))
```

You can also call `proxy-collector` with the same function to return a (not yet registered) `Collector` directly.

The provided function can return a single metric, or a collection of them.

## Usage with pushgateway

Pushgateway is useful for instrumenting short-lived processes (e.g. batch jobs) that may
not live long enough for metrics to be scraped.

To use it, simply send the serialized output of `collect` to the gateway using your http client of choice:

For example, using [hato](https://github.com/gnarroway/hato):
```clojure
(hato.client/post "http://your-gateway.com/metrics/job/somejob/somelabel/foo"
                  {:body (-> (fumi/collect)
                             (fumi/serialize :text))
                   :content-type "text/plain; version=0.0.4"})
```

## License

Released under the MIT License: http://www.opensource.org/licenses/mit-license.php
