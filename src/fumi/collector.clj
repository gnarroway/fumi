(ns ^{:author "George Narroway"}
 fumi.collector
  "Prometheus core metric implementations")

(def metric-name-re #"[a-zA-Z_:][a-zA-Z0-9_:]*")
(def label-name-re #"[a-zA-Z_][a-zA-Z0-9_]*")

;;; Protocols

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

(defprotocol Increaseable
  (-increase [this n opts] "Increase value by `n`, optionally for map `opts.labels`"))

(defprotocol Decreaseable
  (-decrease [this n opts] "Decrease value by `n`, optionally for map `opts.labels`"))

(defprotocol Setable
  (-set [this n opts] "Set value to `n`, optionally for map `opts.labels`"))

(defprotocol Observable
  (-observe [this n opts] "Observes value `n`, optionally for a map `opts.labels`"))

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
     :samples (map (fn [[k v]] (cond-> {:name  name
                                        :value (:value v)}
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
     :samples (map (fn [[k v]] (cond-> {:name  name
                                        :value (:value v)}
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
                (cond-> {:name  (str (clojure.core/name name) "_" (clojure.core/name t))
                         :value (t v)}
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

(defn- throw-if-invalid
  [metric-name description label-names]
  (assert (and (keyword? metric-name) (re-matches metric-name-re (name metric-name)))
          (format "name must be a keyword matching /%s/" metric-name-re))

  (doseq [label label-names]
    (assert (re-matches label-name-re (name label))
            (format "label [%s] does not match /%s/." label label-name-re)))

  (assert (string? description) "description must be provided"))

(defn counter
  "Creates a counter collector.

  `name` a keyword compliant with prometheus naming.
  `options` a map of:

  - `:description`
  - `:label-names` a list of strings or keywords"
  [name {:keys [description label-names] :or {label-names []}}]
  (throw-if-invalid name description label-names)
  (cond-> (->Counter name description label-names)
    (empty? label-names) (assoc :labels {nil {:value 0}})))

(defn gauge
  "Creates a gauge collector.

  `name` a keyword compliant with prometheus naming.
  `options` a map of:

  - `:description`
  - `:label-names` a list of strings or keywords"
  [name {:keys [description label-names] :or {label-names []}}]
  (throw-if-invalid name description label-names)
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
  (throw-if-invalid name description label-names)
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
  (throw-if-invalid name description label-names)
  (let [buckets (distinct (conj (vec buckets) (Double/POSITIVE_INFINITY)))]
    (-> (->Histogram name description label-names buckets)
        (assoc :bucket-values {}))))

(defn- metric-collector
  "Create a collector from some options."
  [metric-name {:keys [type] :as opts}]
  {:pre [(#{:counter :gauge :summary :histogram} type)]}
  (case type
    :counter (counter metric-name opts)
    :gauge (gauge metric-name opts)
    :summary (summary metric-name opts)
    :histogram (histogram metric-name opts)))

(defn ->collector
  "Coerces arguments into a Collectable.

  `coll-name` is a keyworded name of a collector
  `collector-or-opts` is a Collectable or a map of options to create a metric collector`"
  [coll-name collector-or-opts]
  {:pre [(keyword? coll-name)
         (re-matches metric-name-re (name coll-name))]}
  (if (satisfies? Collectable collector-or-opts)
    collector-or-opts
    (metric-collector coll-name collector-or-opts)))

;;; Operations

(defn increase
  [c {:keys [n labels] :or {n 1} :as opts}]
  {:pre [(pos? n)
         (= (set (:label-names c)) (set (keys labels)))]}
  (-increase c n opts))

(defn decrease
  [c {:keys [n labels] :or {n 1} :as opts}]
  {:pre [(pos? n)
         (= (set (:label-names c)) (set (keys labels)))]}
  (-decrease c n opts))

(defn set-n
  [c n {:keys [labels] :as opts}]
  {:pre [(number? n)
         (= (set (:label-names c)) (set (keys labels)))]}
  (-set c n opts))

(defn observe
  [c n {:keys [labels] :as opts}]
  {:pre [(number? n)
         (= (set (:label-names c)) (set (keys labels)))]}
  (-observe c n opts))

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