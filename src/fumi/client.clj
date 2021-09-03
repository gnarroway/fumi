(ns ^{:author "George Narroway"}
 fumi.client
  "Native clojure prometheus client"
  (:require [clojure.core :as core]
            [clojure.string :as string])
  (:import (io.prometheus.client Collector Collector$Type CollectorRegistry
                                 Collector$MetricFamilySamples Collector$MetricFamilySamples$Sample
                                 Counter Gauge Histogram Summary)))

; We keep our own registry atom of the collectors so we can look them up by name
(defonce collectors (atom nil))
(defonce default-registry (CollectorRegistry/defaultRegistry))

; Enable registering default exports if the correct dependency is available
(defmacro include-optional-features []
  (if (try
        (import '(io.prometheus.client.hotspot DefaultExports))
        (catch ClassNotFoundException _ nil))
    '(defn- register-default-exports [registry]
       (DefaultExports/register registry))
    '(defn- register-default-exports [registry]
       (println "io.prometheus/simpleclient_hotspot must be added to use default exports"))))

(include-optional-features)

;;; Registry

(defn ->Type
  [type]
  (Collector$Type/valueOf (-> type
                              name
                              string/upper-case)))
(defn ->Sample
  [s]
  (let [ks (keys (:labels s))]
    (Collector$MetricFamilySamples$Sample. (some-> (:name s) name)
                                           ks
                                           (map (fn [k] (get (:labels s) k)) ks)
                                           (:value s))))

(defn ->MetricFamilySamples
  [x]
  (Collector$MetricFamilySamples. (some-> (:name x) name)
                                  (->Type (:type x))
                                  (:help x)
                                  (map ->Sample (:samples x))))

(defn proxy-collector
  "Turns a function return clojure datastructures into a Collector.

  The function should return a map (or list of maps) with fields:

  - `:name` the name of the metric or family
  - `:type` :counter, :gauge, :histogram, :summary. :info
  - `:help` a string describing the metric.
  - `:samples` a list of maps with keys:
    - `:name` (optional) the name of the metric to replace the parent name if provided.
    - `:value` the value of the metric
    - `:labels` (optional) a map of label-name to label value

  e.g.
  [{:name    \"jvm2\",
    :type    :info,
    :help    \"VM version info\",
    :samples [{:name   \"jvm_info\",
               :value  1.0,
               :labels {\"version\" \"11.0.11+9-LTS\"}}]}]"
  [f]
  (proxy
   [Collector] []
    (collect [& args]
      (let [res (f)]
        (if (map? res)
          (->MetricFamilySamples res)
          (map ->MetricFamilySamples res))))))

(defn ->Collector
  [name opts]
  (let [b (case (:type opts)
            :counter (Counter/build)
            :gauge (Gauge/build)
            :summary (Summary/build)
            :histogram (Histogram/build)
            (throw (IllegalArgumentException. "Unknown metric type")))
        ; name and help must always be set
        b (-> b
              (.name (core/name name))
              (.help (:help opts)))]

    (cond-> b
      (:label-names opts) (.labelNames (into-array String (map core/name (:label-names opts))))
      (and (= :histogram (:type opts))
           (:buckets opts)) (.buckets (into-array Double (map double (:buckets opts))))
      true (.create))))

(defn register!
  "Register a collector and returns a wrapped version of the collector with its options.

  Arguments:

  - `registry` a CollectorRegistry. if not provided, the default-registry will be used.
  - `name` is the name of the metric.
  - `opts` is a custom Collector, a function returning samples, or map of:
    - `:type` :counter, :gauge, :histogram, :summary.
    - `:help` a string describing the metric.
    - `:label-names` (optional) a list of string/keyword dimensions of this metric.
    - `:buckets` (optional, only for histogram), defaulting to `[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]`.
    - `:registry` (optional) the result of calling init!. If not specified, uses the default-registry."
  ([name opts]
   (register! default-registry name opts))
  ([registry name opts]
   (let [registry (or registry default-registry)
         c (cond
             (map? opts) (->Collector name opts)
             (fn? opts) (proxy-collector opts)
             (instance? Collector opts) opts)]
     (.register registry c)
     (let [c' {:label-names (:label-names opts)
               :registry    registry
               :collector   c}]
       (swap! collectors assoc name c')
       c'))))

(defn init!
  "Initialises a registry with config and returns the registry.

  `config` is a map of:

  - `:self-managed?` (optional) if true, the default registry will not be used.
  - `:exclude-defaults?` (optional) exclude default collectors when set to true (default false).
  - `:collectors` a map of collector name to either a `Collectable`, or an options map with keys:
    - `:type` :counter, :gauge, :histogram, :summary.
    - `:help` a string describing the metric.
    - `:label-names` (optional) a list of string/keyword dimensions of this metric.
    - `:buckets` (optional, only for histogram), defaulting to `[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]`."
  [config]
  (let [registry (if (:self-managed? config) (CollectorRegistry.) default-registry)]
    ; Clean up because the java client does not allow modification
    (.clear registry)
    (swap! collectors (fn [xs] (->> xs (remove (fn [[_ v]] (= registry (:registry v)))) (into {}))))
    (doseq [[name opts] (:collectors config)]
      (register! registry name opts))
    (when (:default-exports? config)
      (register-default-exports registry))
    registry))

;;; Operations

(defn- collector-or-child
  "Returns a collector to make observations on.
  Validates labels if the collector is configured with label-names.

  - `c` a name the collector was registered with, or a map of :collector :label-names"
  [c labels]
  (let [collector (if (or (keyword? c) (string? c)) (get @collectors c) c)]
    (when-not collector (throw (IllegalArgumentException. "collector does not exist")))
    (if-let [label-names (:label-names collector)]
      (do (when (not= (set label-names) (set (keys labels))) (throw (IllegalArgumentException. "labels must match label names")))
          (.labels (:collector collector) (into-array String (map (fn [k] (name (get labels k))) label-names))))
      (:collector collector))))

(defn increase!
  "Increase the value of a collector `name`.

  Arguments:

  - `c` a name the collector was registered with, or a collector itself
  - `opts` (optional), a map of:
    - `:n` a positive number to increase the value of the collector by (default 1)`\n
    - `:labels` a map of label name to value - must be provided if the collector has `label-names` defined."
  ([c] (increase! c {}))
  ([c opts]
   (let [{:keys [n labels] :or {n 1}} opts]
     (-> (collector-or-child c labels)
         (.inc n)))))

(defn decrease!
  "Decrease the value of collector `name`.

  Arguments:

  - `c` a name the collector was registered with, or a collector itself
  - `opts` (optional), a map of:
    - `:n` a positive number to decrease the value of the collector by (default 1)`
    - `:labels` a map of label name to value - must be provided if the collector has `label-names` defined."
  ([c] (decrease! c {}))
  ([c opts]
   (let [{:keys [n labels] :or {n 1}} opts]
     (-> (collector-or-child c labels)
         (.dec n)))))

(defn set-n!
  "Sets the value of a collector `name` to `n`.

  Arguments:

  - `c` a name the collector was registered with, or a collector itself
  - `n` a number to set the value of the collector to`
  - `opts` (optional), a map of:
    - `:labels` a map of label name to value - must be provided if the collector has `label-names` defined."
  ([c n] (set-n! c n {}))
  ([c n opts]
   (let [labels (:labels opts)]
     (-> (collector-or-child c labels)
         (.set n)))))

(defn observe!
  "Observe value `n` for collector `name`.

  Arguments:

  - `c` a name the collector was registered with, or a collector itself
  - `n` a number to set the value of the collector to`
  - `opts` (optional), a map of:
    - `:labels` a map of label name to value - must be provided if the collector has `label-names` defined."
  ([c n] (observe! c n {}))
  ([c n opts]
   (let [labels (:labels opts)]
     (-> (collector-or-child c labels)
         (.observe n)))))

(defn collect
  "Collects stats from one or more registries into a list of metrics.

  Uses the default registry if no arguments are provided.
  Otherwise, all desired registries (including the default) must be explicitly provided."
  ([] (collect default-registry))
  ([registry & registries]
   (->> (conj registries registry)
        (distinct)
        (mapcat (fn [registry] (enumeration-seq (.metricFamilySamples registry))))
        (map (fn [m] {:name    (.name m)
                      :type    (keyword (string/lower-case (.name (.type m))))
                      :help    (.help m)
                      :samples (->> (.samples m)
                                    (map (fn [s] (cond-> {:name  (.name s)
                                                          :value (.value s)}
                                                   (some? (.timestampMs s)) (assoc :ts (.timestampMs s))
                                                   (seq (.labelNames s)) (assoc :labels (zipmap (.labelNames s) (.labelValues s))))))
                                    (sort-by (juxt :name :value)))}))
        (sort-by :name))))

;;; Serialization

(defn- label-pair
  [[k v]]
  (format "%s=\"%s\"" (name k) (name v)))

(defn- metric-row
  [parent-name {:keys [name labels value]}]
  (let [label-str (if (seq labels) (str "{" (string/join "," (map label-pair labels)) "}") "")]
    (format "%s%s %s" (core/name (or name parent-name)) label-str value)))

(defmulti serialize
  "Serialize collected metrics to output format."
  (fn [_ type] type))

(defmethod serialize :default
  [metrics _]
  (str (->> metrics
            (map #(string/join "\n" (concat [(format "# HELP %s %s" (name (:name %)) (:help %))
                                             (format "# TYPE %s %s" (name (:name %)) (name (:type %)))]

                                            (map (partial metric-row (:name %)) (:samples %)))))
            (string/join "\n\n"))
       "\n"))

(comment
  ; Use collectors directly
  (def c1 (->Collector "counter1" {:type :counter :help "help me"}))
  ; Need to register it before it gets exposed anywhere
  (.register c1)
  ; Make an observation
  (.inc c1)
  (increase! {:collector c1})

  ; Another one with labels
  (def c2 (->Collector "counter2" {:type :counter :help "help me" :label-names [:foo :bar]}))
  (.register c2)
  (-> c2
      (.labels (into-array String ["foo-v" "bar-v"]))
      (.inc 3))
  (increase! {:collector c2 :label-names [:foo :bar]} {:labels {:foo "1" :bar "2"}})

  ; Get the results
  (collect)
  (serialize *1 :text)

  ; Reset the registry
  (init! {})
  (init! {:collectors {:version-info (io.prometheus.client.hotspot.VersionInfoExports.)}})
  ; Add specific default exporters
  (register! :version-info (io.prometheus.client.hotspot.VersionInfoExports.))

  (register! :constant_counter
             (proxy-collector (fn [] [{:name    "metric"
                                       :type    :counter
                                       :help    "a constant counter"
                                       :samples [{:value 42}]}
                                      {:name    "metric_with_labels",
                                       :type    :gauge,
                                       :help    "a constant gauge with labels",
                                       :samples [{:name   "active_requests",
                                                  :value  42
                                                  :labels {:source "foo"}}]}])))

  ; Include all default exports
  (init! {:default-exports? true})

  (defn collector
    []
    [{:name    "metric",
      :type    :info,
      :help    "VM version info",
      :samples [{:value 1.0}]}
     {:name    "metric_with_sample_name",
      :type    :info,
      :help    "VM version info",
      :samples [{:name  "jvm_info",
                 :value 1.0}]}
     {:name    "metric_with_labels",
      :type    :counter,
      :help    "Some requests",
      :samples [{:name   "request_count",
                 :value  1.0
                 :labels {:source "foo"}}]}])

  (register! :foo (proxy-collector collector)))

(comment
  ;; Create the registry with defaults
  (init!
   {:default-exports? true
    :collectors       {:test_counter   {:type :counter :help "a counter" :label-names [:foo]}
                       :test_gauge     {:type :gauge :help "a gauge"}
                       :test_histogram {:type :histogram :help "a histogram"}}})

  ;; Add more metrics
  (register! :another_counter {:type :counter :help "another counter"})
  (register! :test_summary {:type :summary :help "a summary"})

  ;; Observe some values
  (increase! :test_counter {:n 3 :labels {:foo "bar"}})
  (increase! :test_counter {:n -1 :labels {:foo "bar" :moo "cow"}})
  (increase! :test_counter {:labels {:foo :bar}})

  (set-n! :test_gauge 2.1 {})
  (observe! :test_histogram 0.51 {})

  ;; Output
  (-> (collect)
      (serialize :text)))

(comment
  ;; Create separate registry
  (def my-registry (init!
                    {:self-managed?    true
                     :default-exports? false
                     :collectors       {:test_counter {:type :counter :help "a counter" :label-names [:foo]}}}))

  ;; Add more metrics
  (register! :another_counter {:type :counter :help "another counter" :registry my-registry})

  ;; Observe some values
  (increase! :test_counter {:n 3 :labels {:foo "bar"} :registry my-registry})

  ;; Output
  (-> (collect my-registry)
      (serialize :text)))