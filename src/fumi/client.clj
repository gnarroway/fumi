(ns ^{:author "George Narroway"}
 fumi.client
  "Native clojure prometheus client"
  (:require [clojure.string :as string]
            [fumi.collector.jvm :as jvm]
            [fumi.collector.process :as process]))

(defonce default-registry (atom nil))
(def metric-name-re #"[a-zA-Z_:][a-zA-Z0-9_:]*")
(def label-name-re #"[a-zA-Z_][a-zA-Z0-9_]*")

;;; Protocols

(defprotocol Increaseable
  (-increase [this n opts] "Increase value by `n`, optionally for map `opts.labels`"))

(defprotocol Decreaseable
  (-decrease [this n opts] "Decrease value by `n`, optionally for map `opts.labels`"))

(defprotocol Setable
  (-set [this n opts] "Set value to `n`, optionally for map `opts.labels`"))

(defprotocol Observable
  (-observe [this n opts] "Observes value `n`, optionally for a map `opts.labels`"))

(defprotocol Collectable
  (-collect [this]
    "Returns a list of maps with keys:

    - `:name` string or kw
    - `:help` string
    - `:type` :counter, :gauge, :summary, :histogram
    - `:samples` a list of maps with keys:
      - `:value` number
      - `:labels` (optional) map of label key to value
      - `:name` (optional) string or kw to override the name of the collector in the output"))

(def default-config
  {:jvm_family     (reify Collectable
                     (-collect [_] (jvm/collect)))

   :process_family (reify Collectable
                     (-collect [_] (process/collect)))})


;;; Implementation


(defrecord Counter [name description label-names]
  Increaseable
  (-increase [this n {:keys [labels]}]
    (update-in this [:labels labels]
               (fn [{:keys [value] :or {value 0}}]
                 {:value (+ value n)})))

  Collectable
  (-collect [this]
    {:name    name
     :help    description
     :type    :counter
     :samples (map (fn [[k v]] (cond-> {:name   name
                                        :value  (:value v)}
                                 (seq k) (assoc :labels k)))
                   (:labels this))}))

(defrecord Gauge [name description label-names]
  Increaseable
  (-increase [this n {:keys [labels]}]
    (update-in this [:labels labels]
               (fn [{:keys [value] :or {value 0}}]
                 {:value (+ value n)})))

  Decreaseable
  (-decrease [this n {:keys [labels]}]
    (update-in this [:labels labels]
               (fn [{:keys [value] :or {value 0}}]
                 {:value (- value n)})))

  Setable
  (-set [this n {:keys [labels]}]
    (assoc-in this [:labels labels :value] n))

  Collectable
  (-collect [this]
    {:name    name
     :help    description
     :type    :gauge
     :samples (map (fn [[k v]] (cond-> {:name   name
                                        :value  (:value v)}
                                 (seq k) (assoc :labels k)))
                   (:labels this))}))

(defrecord Summary [name description label-names]
  Observable
  (-observe [this x {:keys [labels]}]
    (update-in this [:labels labels]
               (fn [v] (-> v
                           (update :sum (fnil + 0) x)
                           (update :count (fnil inc 0))))))

  Collectable
  (-collect [this]
    {:name    name
     :help    description
     :type    :summary
     :samples (for [[k v] (:labels this)
                    t [:count :sum]]
                (cond-> {:name   (str (clojure.core/name name) "_" (clojure.core/name t))
                         :value  (t v)}
                  (seq k) (assoc :labels k)))}))

(defrecord Histogram [name description label-names buckets]
  Observable
  (-observe [this x {:keys [labels]}]
    (let [b (first (remove #(< % x) buckets))]
      (update-in this [:labels labels]
                 (fn [v] (-> v
                             (update-in [:bucket-values b] (fnil inc 0))
                             (update :sum (fnil + 0) x)
                             (update :count (fnil inc 0)))))))

  Collectable
  (-collect [this]
    {:name    name
     :help    description
     :type    :histogram
     :samples (->> (:labels this)
                   (mapcat (fn [[k v]]
                             [(let [cumulative-buckets (reduce (fn [acc x]
                                                                 (assoc acc x (+ (or (get-in v [:bucket-values x]) 0)
                                                                                 (apply max (or (vals acc) [0]))))) {}
                                                               buckets)]
                                (map (fn [b] {:name   (str (clojure.core/name name) "_buckets")
                                              :labels (assoc k :le (if (= (Double/POSITIVE_INFINITY) b) "+Inf" (str b)))
                                              :value  (cumulative-buckets b)})
                                     buckets))

                              (map (fn [t]
                                     (cond-> {:name  (str (clojure.core/name name) "_" (clojure.core/name t))
                                              :value (t v)}
                                       (seq k) (assoc :labels k))) [:count :sum])]))
                   (apply concat))}))

(defn counter
  "Creates a counter collector.

  `name` a keyword compliant with prometheus naming.
  `options` a map of:

  - `:description`
  - `:label-names` a list of strings or keywords"
  [name {:keys [description label-names] :or {label-names []}}]
  (cond-> (->Counter name description label-names)
    (empty? label-names) (assoc :labels {nil {:value 0}})))

(defn gauge
  "Creates a gauge collector.

  `name` a keyword compliant with prometheus naming.
  `options` a map of:

  - `:description`
  - `:label-names` a list of strings or keywords"
  [name {:keys [description label-names] :or {label-names []}}]
  (cond-> (->Gauge name description label-names)
    (empty? label-names) (assoc :labels {nil {:value 0}})))

(defn summary
  "Creates a summary collector.

  `name` a keyword compliant with prometheus naming.
  `options` a map of:

  - `:description`
  - `:label-names` a list of strings or keywords"
  [name {:keys [description label-names] :or {label-names []}}]
  {:pre [(not (.contains (map keyword label-names) :quantile))]}
  (->Summary name description label-names))

(defn histogram
  "Creates a histogram collector.

  `name` a keyword compliant with prometheus naming.
  `options` a map of:

  - `:description`
  - `:label-names` a list of strings or keywords
  - `:buckets` a list of increasing bucket cutoffs. +Inf will be appended to the end.
    Defaults to [0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]"
  [name {:keys [description label-names buckets]
         :or   {label-names [] buckets [0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]}}]
  {:pre [(not (.contains (map keyword label-names) :le))
         (seq buckets)
         (apply < buckets)]}
  (let [buckets (distinct (conj (vec buckets) (Double/POSITIVE_INFINITY)))]
    (-> (->Histogram name description label-names buckets)
        (assoc :bucket-values {}))))


;;; Registry


(defn- init-collector
  "Create a collector and adds it to a map."
  [m metric-name {:keys [type description label-names] :as opts}]
  {:pre [(keyword? metric-name)
         (re-matches metric-name-re (name metric-name))
         (every? #(re-matches label-name-re (name %)) label-names)
         (string? description)
         (#{:counter :gauge :summary :histogram} type)]}

  (assoc m metric-name
         (case type
           :counter (counter metric-name opts)
           :gauge (gauge metric-name opts)
           :summary (summary metric-name opts)
           :histogram (histogram metric-name opts))))

(defn- init
  "Initialises from a config map.

  Returns a map of collectors."
  [config]
  (cond-> (reduce (fn [acc [name opts]]
                    (init-collector acc name opts)) {} (:collectors config))

    (not (:exclude-defaults? config)) (merge default-config)))

(defn init!
  "Initialises a registry with config."
  ([config]
   (init! default-registry config))
  ([registry config]
   (reset! registry (init config))
   registry))

(defn register!
  "Register a collector."
  ([name opts]
   (register! default-registry name opts))
  ([registry name opts]
   (swap! registry #(init-collector % name opts))))


;;; Operations


(defn- increase
  "Increase the value of a collector `c` by `n` (>= 0, default 1).
  If the collector has `label-names` defined, `labels` - a map of label name to value - must be provided."
  [c {:keys [n labels] :or {n 1} :as opts}]
  {:pre [(pos? n)
         (= (set (:label-names c)) (set (keys labels)))]}
  (-increase c n opts))

(defn- decrease
  "Decrease the value of a collector `c` by `n` (>= 0, default 1).
  If the collector has `label-names` defined, `labels` - a map of label name to value - must be provided."
  [c {:keys [n labels] :or {n 1} :as opts}]
  {:pre [(pos? n)
         (= (set (:label-names c)) (set (keys labels)))]}
  (-decrease c n opts))

(defn- set-n
  "Sets the value of a collector `c` to `n`.
  If the collector has `label-names` defined, `labels` - a map of label name to value - must be provided."
  [c n {:keys [labels] :as opts}]
  {:pre [(number? n)
         (= (set (:label-names c)) (set (keys labels)))]}
  (-set c n opts))

(defn- observe
  "Observe value `n` for collector `c`.
  If the collector has `label-names` defined, `labels` - a map of label name to value - must be provided."
  [c n {:keys [labels] :as opts}]
  {:pre [(number? n)
         (= (set (:label-names c)) (set (keys labels)))]}
  (-observe c n opts))

(defn increase!
  ([name opts] (increase! default-registry name opts))
  ([registry name opts]
   (swap! registry update name increase opts)))

(defn decrease!
  ([name opts] (decrease! default-registry name opts))
  ([registry name opts]
   (swap! registry update name decrease opts)))

(defn set-n!
  ([name n opts] (set-n! default-registry name n opts))
  ([registry name n opts]
   (swap! registry update name set-n n opts)))

(defn observe!
  ([name n opts] (observe! default-registry name n opts))
  ([registry name n opts]
   (swap! registry update name observe n opts)))

(defn collect
  "Collects stats from one or more registries into a list of metrics."
  ([] (collect default-registry))
  ([registry & registries] (->> (conj registries registry default-registry)
                                (distinct)
                                (map deref)
                                (apply merge)
                                (map (comp -collect val))
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

(init! default-registry)

(comment
  ;; Create the registry with defaults
  (init!
   {:exclude-defaults? false
    :collectors        {:test_counter   {:type :counter :description "a counter" :label-names [:foo]}
                        :test_gauge     {:type :gauge :description "a gauge"}
                        :test_histogram {:type :histogram :description "a histogram"}}})

  ;; Add more metrics
  (register! :another_counter {:type :counter :description "another counter"})
  (register! :test_summary {:type :summary :description "a summary"})

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
                    {:exclude-defaults? true
                     :collectors        {:test_counter   {:type :counter :description "a counter" :label-names [:foo]}
                                         :test_gauge     {:type :gauge :description "a gauge"}
                                         :test_histogram {:type :histogram :description "a histogram"}}}))

  ;; Add more metrics
  (register! my-registry :another_counter {:type :counter :description "another counter"})

  ;; Observe some values
  (increase! my-registry :test_counter {:n 3 :labels {:foo "bar"}})
  (set-n! my-registry :test_gauge 2.1 {})
  (observe! my-registry :test_histogram 0.51 {})

  (deref my-registry)

  ;; Output
  (-> (collect my-registry)
      (serialize :text)))

(comment
  ;; Perf testing
  (def reg (init! (atom nil)
                  {:exclude-defaults? true
                   :collectors        {:test_counter   {:type :counter :description "a counter" :label-names [:foo]}
                                       :test_gauge     {:type :gauge :description "a gauge"}
                                       :test_histogram {:type :histogram :description "a histogram"}}}))

  (def thread-count 4)
  (def ops-per-thread 250000)
  (defn run [] (dotimes [x ops-per-thread]
                 (case (mod x 4)
                   0 (increase! reg :test_counter {:n (inc (rand-int 5)) :labels {:foo "bar"}})
                   1 (increase! reg :test_gauge {:n (inc (rand-int 5))})
                   2 (set-n! reg :test_gauge (inc (rand-int 5)) {})
                   3 (observe! reg :test_histogram (rand 2) {}))))

  (let [start (System/currentTimeMillis)]
    (dorun (apply pcalls (repeat thread-count run)))
    (println (-> (collect reg)
                 (serialize :text)))
    (let [total (- (System/currentTimeMillis) start)]
      (println "TOOK: " total)
      (println (format "%2f ops per sec" (double (/ (* thread-count ops-per-thread 1000) total)))))))

(comment
  ;; Counter
  (-> (counter :c {:description "test"})
      (increase {:n 2})
      (-collect))

  (-> (counter :c {:description "test" :label-names [:foo]})
      (increase {:labels {:foo "bar"}})
      (increase {:labels {:foo "bob"}})
      (-collect))

  ;; Gauge
  (-> (gauge :g {:description "test"})
      (increase {:n 2})
      (decrease {:n 0.5})
      (-collect))

  (-> (gauge :g {:description "test" :label-names [:foo]})
      (increase {:n 2 :labels {:foo "bar"}})
      (decrease {:n 0.5 :labels {:foo "bar"}})
      (set-n 0.5 {:labels {:foo "baz"}})
      (-collect))

  ;; Summary
  (-> (summary :s {:description "test"})
      (observe 1 {})
      (observe 2 {})
      (-collect))

  (-> (summary :s {:description "test" :label-names [:foo]})
      (observe 1 {:labels {:foo "bar"}})
      (observe 2 {:labels {:foo "baz"}})
      (-collect))

  ;; Histogram
  (-> (histogram :h {:description "test"})
      (observe 0.5 {})
      (observe 0.01 {})
      (observe 7 {})
      (-collect))

  (-> (histogram :h {:description "test" :label-names [:path]})
      (observe 0.5 {:labels {:path "/foo"}})
      (observe 0.01 {:labels {:path "/bar"}})
      (observe 7 {:labels {:path "/foo"}})
      (-collect)))