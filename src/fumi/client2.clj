(ns ^{:author "George Narroway"}
  fumi.client2
  "Native clojure prometheus client"
  (:require [clojure.string :as string]
            [fumi.collector.jvm :as jvm]
            [fumi.collector.process :as process]
            [fumi.collector2 :as collector]))

(defonce collectors (atom nil))
(defonce default-registry (io.prometheus.client.CollectorRegistry/defaultRegistry))

(def default-config
  {:jvm_family     (jvm/collector)
   :process_family (process/collector)})

;;; Registry

(defn ->collector
  [name opts]
  (let [b (case (:type opts)
            :counter (io.prometheus.client.Counter/build)
            :gauge (io.prometheus.client.Gauge/build)
            :summary (io.prometheus.client.Summary/build)
            :histogram (io.prometheus.client.Histogram/build)
            :else (throw (IllegalArgumentException. "Unknown metric type")))
        ; name and help must always be set
        b (-> b
              (.name (clojure.core/name name))
              (.help (:help opts)))]

    (cond-> b
            (:label-names opts) (.labelNames (into-array String (map clojure.core/name (:label-names opts))))
            (and (= :histogram (:type opts))
                 (:buckets opts)) (.buckets (into-array Double (map double (:buckets opts))))
            true (.create))))

(defn- init
  "Initialises from a config map.

  Returns a map of collectors."
  [config]
  (cond-> (reduce (fn [acc [name opts]]
                    (assoc acc name (->collector name opts))) {} (:collectors config))

          (not (:exclude-defaults? config)) (merge default-config)))
; TODO :exclude-defaults? on init
(defn register!
  "Register a collector.

  Arguments:

  - `name` is the name of the metric.
  - `opts` is a custom Collector, or map of:
    - `:type` :counter, :gauge, :histogram, :summary.
    - `:help` a string describing the metric.
    - `:label-names` (optional) a list of string/keyword dimensions of this metric.
    - `:buckets` (optional, only for histogram), defaulting to `[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]`.
    - `:registry` (optional) the result of calling init!. If not specified, uses the default-registry."
  [name opts]
  (let [registry (or (:registry opts) default-registry)
        c (if (instance? io.prometheus.client.Collector opts) opts (->collector name opts))]
    (.register registry c)
    (swap! collectors assoc name {:collector-opts opts
                                  :collector c})))

(defn init!
  "Initialises a registry with config.

  `config` is a map of:

  - `:self-managed?` (optional) if true, the default registry will not be used.
  - `:exclude-defaults?` (optional) exclude default collectors when set to true (default false).
  - `:collectors` a map of collector name to either a `Collectable`, or an options map with keys:
    - `:type` :counter, :gauge, :histogram, :summary.
    - `:help` a string describing the metric.
    - `:label-names` (optional) a list of string/keyword dimensions of this metric.
    - `:buckets` (optional, only for histogram), defaulting to `[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]`."
  [config]
  (let [registry (if (:self-managed? config) (io.prometheus.client.Collector.) default-registry)]
    ; Clean up because the java client does not allow modification
    (.clear registry)
    (reset! collectors nil)
    (doseq [[name opts] (:collectors config)]
      (register! name (assoc opts :registry registry)))
    registry))


; TODO (init! default-registry)

;;; Operations

(defn- throw-if-not-registered
  [c name]
  (assert (some? c) (format "%s has not been registered." name)))

(defn- throw-if-invalid
  "Basic pre-validation before mutating collectors.

  - `c` is a value from the `collectors` store"
  [c name opts]
  (assert (some? (:collector c)) (format "%s has not been registered." name))
  (when-let [label-names (:label-names (:collector-opts c))]
    (assert (= label-names (keys (:labels opts))) "labels must match label names")))

(defn increase!
  "Increase the value of a collector `name`.

  Arguments:

  - `name` a name the collector was registered with
  - `opts` (optional), a map of:
    - `:n` a positive number to increase the value of the collector by (default 1)`\n
    - `:labels` a map of label name to value - must be provided if the collector has `label-names` defined.
    - `:registry` a registry (as returned from `init!`) if not using the default-registry"
  ([name] (increase! name {}))
  ([name opts]
   (let [{:keys [collector collector-opts] :as c} (get @collectors name)
         {:keys [n labels] :or {n 1}} opts]
     (throw-if-invalid c name opts)
     (cond-> collector
             (:label-names collector-opts) (.labels (into-array String (map (fn [k] (clojure.core/name (get labels k))) (:label-names collector-opts))))
             true (.inc n)))))

(defn decrease!
  "Decrease the value of collector `name`.

  Arguments:

  - `name` a name the collector was registered with
  - `opts` (optional), a map of:
    - `:n` a positive number to decrease the value of the collector by (default 1)`
    - `:labels` a map of label name to value - must be provided if the collector has `label-names` defined.
    - `:registry` a registry (as returned from `init!`) if not using the default-registry"
  ([name] (decrease! name {}))
  ([name opts]
   (let [{:keys [collector collector-opts] :as c} (get @collectors name)
         {:keys [n labels] :or {n 1}} opts]
     (throw-if-invalid c name opts)
     (cond-> collector
             (:label-names collector-opts) (.labels (into-array String (map (fn [k] (clojure.core/name (get labels k))) (:label-names collector-opts))))
             true (.dec n)))))

(defn set-n!
  "Sets the value of a collector `name` to `n`.

  Arguments:

  - `name` a name the collector was registered with
  - `n` a number to set the value of the collector to`
  - `opts` (optional), a map of:
    - `:labels` a map of label name to value - must be provided if the collector has `label-names` defined.
    - `:registry` a registry (as returned from `init!`) if not using the default-registry"
  ([name n] (set-n! name n {}))
  ([name n opts]
   (let [r (or (:registry opts) default-registry)]
     (throw-if-not-registered @r name)
     (when-not (empty? (:labels opts))
       (swap! r update name collector/prepare opts))
     (collector/set-n (get @r name) n opts))))

(defn observe!
  "Observe value `n` for collector `name`.

  Arguments:

  - `name` a name the collector was registered with
  - `n` a number to set the value of the collector to`
  - `opts` (optional), a map of:
    - `:labels` a map of label name to value - must be provided if the collector has `label-names` defined.
    - `:registry` a registry (as returned from `init!`) if not using the default-registry"
  ([name n] (observe! name n {}))
  ([name n opts]
   (let [r (or (:registry opts) default-registry)]
     (throw-if-not-registered @r name)
     (when-not (empty? (:labels opts))
       (swap! r update name collector/prepare opts))
     (collector/observe (get @r name) n opts))))

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
                      :type    (keyword (clojure.string/lower-case (.name (.type m))))
                      :help    (.help m)
                      :samples (map (fn [s] (cond-> {:name  (.name s)
                                                     :value (.value s)}
                                                    (some? (.timestampMs s)) (assoc :ts (.timestampMs s))
                                                    (seq (.labelNames s)) (assoc :labels (zipmap (.labelNames s) (.labelValues s))))) (.samples m))})))))

;;; Serialization

(defn- label-pair
  [[k v]]
  (format "%s=\"%s\"" (name k) (name v)))

(defn- metric-row
  [parent-name {:keys [name labels value]}]
  (let [label-str (if (seq labels) (str "{" (string/join "," (map label-pair labels)) "}") "")]
    (format "%s%s %s" (clojure.core/name (or name parent-name)) label-str value)))

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

  (def foo (-> (io.prometheus.client.Counter/build)
               (.name "requests_total5_total")
               ;(.help "foo")
               (.register)))



  (->my-collector "foo" {:type :counter :help "moo" :buckets [0.005 1]})
  (->my-collector "hello" {:type :summary :help "moo" :label-names ["quantile2"]})


  (.inc foo)

  (map identity)



  (register! :another_counter {:type :counter :help "another counter"})

  (collect)
  (collect (io.prometheus.client.CollectorRegistry/defaultRegistry))

  (init! {})

  (serialize *1 :text)

  )

(comment


  ;; Create the registry with defaults
  (init!
    {:exclude-defaults? false
     :collectors        {:test_counter   {:type :counter :help "a counter" :label-names [:foo]}
                         :test_gauge     {:type :gauge :help "a gauge"}
                         :test_histogram {:type :histogram :help "a histogram"}}})

  ;; Add more metrics
  (register! :another_counter {:type :counter :help "another counter"})
  (register! :test_summary {:type :summary :help "a summary"})

  ;; Observe some values
  (increase! :test_counter {:n 3 :labels {:foo "bar"}})
  (increase! :test_counter {:n -1 :labels {:foo "bar" :moo "cow"}})
  (increase! :test_counter {:labels {:foo :bar }})


  (set-n! :test_gauge 2.1 {})
  (observe! :test_histogram 0.51 {})

  (deref default-registry)

  ;; Output
  (-> (collect)
      (serialize :text)))

(comment
  ;; Create separate registry
  (def my-registry (init!
                     {:self-managed?     true
                      :exclude-defaults? true
                      :collectors        {:test_counter {:type :counter :help "a counter" :label-names [:foo]}}}))

  ;; Add more metrics
  (register! :another_counter {:type :counter :help "another counter" :registry my-registry})

  ;; Observe some values
  (increase! :test_counter {:n 3 :labels {:foo "bar"} :registry my-registry})

  (deref my-registry)

  ;; Output
  (-> (collect my-registry)
      (serialize :text)))
(:label-names collector-opts)
(comment
  ;; Perf testing
  (def reg (init! {:exclude-defaults? true
                   :collectors        {;:test_counter   {:type :counter :help "a counter" :label-names [:foo]}
                                       ;:test_gauge     {:type :gauge :help "a gauge"}
                                       :test_histogram  {:type :histogram :help "a histogram" :label-names [:foo]}
                                       :test_histogram2 {:type :histogram2 :help "a histogram" :label-names [:foo]}
                                       ;:test_summary {:type :summary :help "a histogram"}
                                       }}))

  (def thread-count 4)
  (def ops-per-thread 250000)
  (defn run [] (dotimes [x ops-per-thread]
                 (let [n (rand 2)]
                   ;(increase! :test_gauge {:n n :registry reg})
                   (let [k (str (rand-int 2))]

                     (observe! :test_histogram n {:registry reg :labels {:foo k}})
                     #_(observe! :test_histogram2 n {:registry reg :labels {:foo k}})))
                 #_(let [n (inc (rand-int 5))]
                     (increase! :test_gauge {:n n :registry reg})
                     ;(observe! :test_summary n {:registry reg})
                     )
                 #_(case (mod x 3)
                     0 (increase! :test_counter {:n (inc (rand-int 5)) :labels {:foo "bar"} :registry reg})
                     1 (increase! :test_gauge {:n (inc (rand-int 5)) :registry reg})
                     2 (set-n! :test_gauge (inc (rand-int 5)) {:registry reg})
                     ;3 (observe! :test_histogram (rand 2) {:registry reg})
                     )))

  (let [start (System/currentTimeMillis)]
    (dorun (apply pcalls (repeat thread-count run)))
    (println (-> (collect reg)
                 (serialize :text)))
    (let [total (- (System/currentTimeMillis) start)]
      (println "TOOK: " total)
      (println (format "%2f ops per sec" (double (/ (* thread-count ops-per-thread 1000) total)))))))

