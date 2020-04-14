(ns ^{:author "George Narroway"}
 fumi.client
  "Native clojure prometheus client"
  (:require [clojure.string :as string]
            [fumi.collector.jvm :as jvm]
            [fumi.collector.process :as process]
            [fumi.collector :as collector]
            [fumi.collector :as metric]))

(defonce default-registry (atom nil))

(def default-config
  {:jvm_family     (jvm/collector)
   :process_family (process/collector)})

;;; Registry


(defn- init
  "Initialises from a config map.

  Returns a map of collectors."
  [config]
  (cond-> (reduce (fn [acc [name opts]]
                    (assoc acc name (metric/->collector name opts))) {} (:collectors config))

    (not (:exclude-defaults? config)) (merge default-config)))

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
  (let [registry (if (:self-managed? config) (atom nil) default-registry)]
    (reset! registry (init config))
    registry))

(defn register!
  "Register a collector.

  Arguments:

  - `name` is the name of the metric.
  - `opts` is a map of:
    - `:type` :counter, :gauge, :histogram, :summary.
    - `:help` a string describing the metric.
    - `:label-names` (optional) a list of string/keyword dimensions of this metric.
    - `:buckets` (optional, only for histogram), defaulting to `[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]`.
    - `:registry` (optional) the result of calling init!. If not specified, uses the default-registry."
  [name opts]
  (let [registry (or (:registry opts) default-registry)]
    (swap! registry assoc name (metric/->collector name opts))))

(init! default-registry)

;;; Operations

(defn- throw-if-not-registered
  [r name]
  (assert (contains? r name) (format "%s has not been registered." name)))

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
   (let [r (or (:registry opts) default-registry)]
     (throw-if-not-registered @r name)
     (when-not (empty? (:labels opts))
       (swap! r update name metric/prepare opts))
     (metric/increase (get @r name) opts))))

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
   (let [r (or (:registry opts) default-registry)]
     (throw-if-not-registered @r name)
     (when-not (empty? (:labels opts))
       (swap! r update name metric/prepare opts))
     (metric/decrease (get @r name) opts))))

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
       (swap! r update name metric/prepare opts))
     (metric/set-n (get @r name) n opts))))

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

     (if (instance? fumi.collector.Histogram (get @r name))
       (swap! r update name metric/observe n opts)
       (do
         (when-not (empty? (:labels opts))
           (swap! r update name metric/prepare opts))

         (metric/observe (get @r name) n opts))))))

(defn collect
  "Collects stats from one or more registries into a list of metrics.
  Default registry is always included."
  ([] (collect default-registry))
  ([registry & registries] (->> (conj registries registry)
                                (distinct)
                                (map deref)
                                (apply merge)
                                (map (comp collector/-collect val))
                                (reduce (fn [acc x] (if (sequential? x)
                                                      (concat acc x)
                                                      (conj acc x))) []))))

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
                                             (format "# HELP %s %s" (name (:name %)) (name (:type %)))]

                                            (map (partial metric-row (:name %)) (:samples %)))))
            (string/join "\n\n"))
       "\n"))

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

(comment
  ;; Perf testing
  (def reg (init! {:exclude-defaults? true
                   :collectors        {:test_counter   {:type :counter :help "a counter" :label-names [:foo]}
                                       :test_gauge     {:type :gauge :help "a gauge"}
                                       :test_histogram {:type :histogram :help "a histogram"}}}))

  (def thread-count 4)
  (def ops-per-thread 250000)
  (defn run [] (dotimes [x ops-per-thread]
                 (case (mod x 4)
                   0 (increase! :test_counter {:n (inc (rand-int 5)) :labels {:foo "bar"} :registry reg})
                   1 (increase! :test_gauge {:n (inc (rand-int 5)) :registry reg})
                   2 (set-n! :test_gauge (inc (rand-int 5)) {:registry reg})
                   3 (observe! :test_histogram (rand 2) {:registry reg}))))

  (let [start (System/currentTimeMillis)]
    (dorun (apply pcalls (repeat thread-count run)))
    (println (-> (collect reg)
                 (serialize :text)))
    (let [total (- (System/currentTimeMillis) start)]
      (println "TOOK: " total)
      (println (format "%2f ops per sec" (double (/ (* thread-count ops-per-thread 1000) total)))))))

