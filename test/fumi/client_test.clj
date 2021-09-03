(ns fumi.client-test
  (:require [clojure.test :refer :all]
            [fumi.client :as fc]))

(deftest test-registry
  (testing "validation"
    (is (thrown? IllegalArgumentException (fc/increase! :not_registered))))

  (testing "default"
    (let [r (fc/init! {:collectors {:c {:type :counter, :help "c2"}}})
          names (->> (fc/collect) (map #(-> % :name name)))]
      (is (= 1 (count (fc/collect))) "collect without args uses default-registry")
      (is (= fc/default-registry r))
      (is (not (some #(clojure.string/starts-with? % "jvm_") names)) "does not include jvm metrics by default"))

    (let [_ (fc/init! {:default-exports? true})
          names (->> (fc/collect) (map #(-> % :name name)))]
      (is (true? (some #(clojure.string/starts-with? % "jvm_") names)) "includes jvm metrics if default exports are enabled")
      (is (true? (some #(clojure.string/starts-with? % "process_") names)) "includes process metrics if default exports are enabled")))

  (testing "self-managed"
    (let [r (fc/init! {:self-managed? true
                       :collectors    {:c {:type :counter, :help "c1"}}})
          names (->> (fc/collect r) (map #(-> % :name name)))]
      (is (= 1 (count (fc/collect r))))
      (is (not= fc/default-registry r))
      (is (not (some #(clojure.string/starts-with? % "jvm_") names)) "does not include jvm metrics by default"))

    (let [r (fc/init! {:default-exports? true})
          names (->> (fc/collect r) (map #(-> % :name name)))]
      (is (true? (some #(clojure.string/starts-with? % "jvm_") names)) "includes jvm metrics if default exports are enabled")
      (is (true? (some #(clojure.string/starts-with? % "process_") names)) "includes process metrics if default exports are enabled")))

  (testing "multiple registries"
    (let [r1 (fc/init! {:self-managed? true
                        :collectors    {:c1 {:type :counter, :help "c1"}}})
          r2 (fc/init! {:self-managed? true
                        :collectors    {:c2 {:type :counter, :help "c2"}}})]
      (fc/increase! :c1 {:registry r1})
      (fc/increase! :c2 {:registry r2 :n 2})
      (is (= #{"c1" "c2"} (set (map :name (fc/collect r1 r2)))) "first registry works even after initing a second"))))

(deftest test-custom-collector
  (let [r (fc/init! {:collectors {:c (fn [] [{:help    "sample adds _total suffix"
                                              :name    "a"
                                              :samples [{:name "a", :value 42.0}]
                                              :type    :counter}
                                             {:help    "metric removes _total suffix"
                                              :name    "b_total"
                                              :samples [{:name "b", :value 42.0}]
                                              :type    :counter}
                                             {:help    "labels work"
                                              :name    "c"
                                              :samples [{:name "c", :value 42.0, :labels {:src "foo"}}]
                                              :type    :counter}])}})]
    (is (= [{:help    "sample adds _total suffix"
             :name    "a"
             :samples [{:name  "a_total"
                        :value 42.0}]
             :type    :counter}
            {:help    "metric removes _total suffix"
             :name    "b"
             :samples [{:name  "b_total"
                        :value 42.0}]
             :type    :counter}
            {:help    "labels work"
             :name    "c"
             :samples [{:labels {:src "foo"}
                        :name   "c_total"
                        :value  42.0}]
             :type    :counter}]
           (fc/collect r)) "java client adjusts suffixes for openmetrics compatibility")))

(defn- without-created-sample
  "Removes auto-added samples like <name>_created since they have a dynamic timestamp which is annoying to test."
  [x]
  (update x :samples (fn [ss] (remove (fn [s] (clojure.string/ends-with? (:name s) "created")) ss))))

(deftest ^:integration test-counter
  (fc/init! {:collectors {:c {:type :counter, :help "ch"}}})
  (is (= [{:help "ch" :name "c" :type :counter :samples [{:name "c_total" :value 0.0}]}]
         (map without-created-sample (fc/collect))))

  (fc/increase! :c)
  (is (= [{:help "ch" :name "c" :type :counter :samples [{:name "c_total" :value 1.0}]}]
         (map without-created-sample (fc/collect))))

  (fc/increase! :c {:n 1.1})
  (is (= [{:help "ch" :name "c" :type :counter :samples [{:name "c_total" :value 2.1}]}]
         (map without-created-sample (fc/collect))))

  (fc/register! :c2 {:type :counter :help "ch2"})
  (is (= [{:help "ch" :name "c" :type :counter :samples [{:name "c_total" :value 2.1}]}
          {:help "ch2" :name "c2" :type :counter :samples [{:name "c2_total" :value 0.0}]}]
         (map without-created-sample (fc/collect))))

  (is (= (str "# HELP c ch\n"
              "# TYPE c counter\n"
              "c_total 2.1\n"
              "\n"
              "# HELP c2 ch2\n"
              "# TYPE c2 counter\n"
              "c2_total 0.0\n")
         (-> (map without-created-sample (fc/collect)) (fc/serialize :text)))))

(deftest ^:integration test-counter-with-labels
  (fc/init! {:collectors {:c {:type :counter, :help "cd" :label-names [:foo :bar]}}})
  (is (= [{:help "cd" :name "c" :samples [] :type :counter}]
         (fc/collect)))

  (fc/increase! :c {:labels {:foo "foo" :bar "bar"}})
  (fc/increase! :c {:n 1.1 :labels {:foo "foo" :bar "baz"}})
  (is (= [{:help    "cd"
           :name    "c"
           :samples [{:labels {"bar" "bar" "foo" "foo"}
                      :name   "c_total"
                      :value  1.0}
                     {:labels {"bar" "baz" "foo" "foo"}
                      :name   "c_total"
                      :value  1.1}]
           :type    :counter}]
         (map without-created-sample (fc/collect))))

  (is (= (str "# HELP c cd\n"
              "# TYPE c counter\n"
              "c_total{foo=\"foo\",bar=\"bar\"} 1.0\n"
              "c_total{foo=\"foo\",bar=\"baz\"} 1.1\n")
         (-> (map without-created-sample (fc/collect)) (fc/serialize :text)))))

(deftest ^:integration test-gauge
  (fc/init! {:collectors {:g {:type :gauge, :help "gh"}}})
  (is (= [{:help "gh" :name "g" :samples [{:value 0.0 :name "g"}] :type :gauge}]
         (fc/collect)))

  (fc/increase! :g)
  (is (= [{:help "gh" :name "g" :samples [{:value 1.0 :name "g"}] :type :gauge}]
         (fc/collect)))

  (fc/decrease! :g {:n 0.1})
  (is (= [{:help "gh" :name "g" :samples [{:value 0.9 :name "g"}] :type :gauge}]
         (fc/collect)))

  (fc/set-n! :g 2.0)
  (is (= [{:help "gh" :name "g" :samples [{:value 2.0 :name "g"}] :type :gauge}]
         (fc/collect)))

  (fc/register! :g2 {:type :gauge :help "gh2"})
  (is (= [{:help "gh" :name "g" :samples [{:value 2.0 :name "g"}] :type :gauge}
          {:help "gh2" :name "g2" :samples [{:value 0.0 :name "g2"}] :type :gauge}]
         (fc/collect)))

  (is (= (str "# HELP g gh\n"
              "# TYPE g gauge\n"
              "g 2.0\n"
              "\n"
              "# HELP g2 gh2\n"
              "# TYPE g2 gauge\n"
              "g2 0.0\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-gauge-with-labels
  (fc/init! {:collectors {:g {:type :gauge, :help "gd" :label-names [:foo :bar]}}})
  (is (= [{:help "gd" :name "g" :samples [] :type :gauge}]
         (fc/collect)))

  (fc/increase! :g {:labels {:foo "foo" :bar "bar"}})
  (fc/increase! :g {:n 1.1 :labels {:foo "foo" :bar "baz"}})
  (fc/set-n! :g 2 {:labels {:foo "foo" :bar "bam"}})
  (is (= [{:help    "gd"
           :name    "g"
           :samples [{:labels {"bar" "bar" "foo" "foo"}
                      :name   "g"
                      :value  1.0}
                     {:labels {"bar" "baz" "foo" "foo"}
                      :name   "g"
                      :value  1.1}
                     {:labels {"bar" "bam" "foo" "foo"}
                      :name   "g"
                      :value  2.0}]
           :type    :gauge}]
         (fc/collect)))

  (is (= (str "# HELP g gd\n"
              "# TYPE g gauge\n"
              "g{foo=\"foo\",bar=\"bar\"} 1.0\n"
              "g{foo=\"foo\",bar=\"baz\"} 1.1\n"
              "g{foo=\"foo\",bar=\"bam\"} 2.0\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-summary
  (fc/init! {:collectors {:s {:type :summary, :help "sd"}}})
  (is (= [{:help    "sd"
           :name    "s"
           :samples [{:name  "s_count"
                      :value 0.0}
                     {:name  "s_sum"
                      :value 0.0}]
           :type    :summary}]
         (map without-created-sample (fc/collect))))

  (fc/observe! :s 1.0)
  (is (= [{:help    "sd"
           :name    "s"
           :samples [{:name "s_count" :value 1.0}
                     {:name "s_sum" :value 1.0}]
           :type    :summary}]
         (map without-created-sample (fc/collect))))

  (fc/observe! :s 0.1)
  (is (= [{:help    "sd"
           :name    "s"
           :samples [{:name "s_count" :value 2.0}
                     {:name "s_sum" :value 1.1}]
           :type    :summary}]
         (map without-created-sample (fc/collect))))

  (fc/register! :s2 {:type :summary :help "sd2"})
  (is (= [{:help    "sd"
           :name    "s"
           :samples [{:name "s_count" :value 2.0}
                     {:name "s_sum" :value 1.1}]
           :type    :summary}
          {:help    "sd2"
           :name    "s2"
           :samples [{:name  "s2_count"
                      :value 0.0}
                     {:name  "s2_sum"
                      :value 0.0}]
           :type    :summary}]
         (map without-created-sample (fc/collect))))

  (is (= (str "# HELP s sd\n"
              "# TYPE s summary\n"
              "s_count 2.0\n"
              "s_sum 1.1\n"
              "\n"
              "# HELP s2 sd2\n"
              "# TYPE s2 summary\n"
              "s2_count 0.0\n"
              "s2_sum 0.0\n")
         (-> (map without-created-sample (fc/collect)) (fc/serialize :text)))))

(deftest ^:integration test-summary-with-labels
  (fc/init! {:collectors {:s {:type :summary, :help "sd" :label-names [:foo :bar]}}})
  (is (= [{:help "sd" :name "s" :samples [] :type :summary}]
         (fc/collect)))

  (fc/observe! :s 1.0 {:labels {:foo "foo" :bar "bar"}})
  (fc/observe! :s 0.1 {:labels {:foo "foo" :bar "baz"}})
  (is (= [{:help    "sd"
           :name    "s"
           :samples [{:labels {"bar" "baz" "foo" "foo"}
                      :name   "s_count"
                      :value  1.0}
                     {:labels {"bar" "bar" "foo" "foo"}
                      :name   "s_count"
                      :value  1.0}
                     {:labels {"bar" "baz" "foo" "foo"}
                      :name   "s_sum"
                      :value  0.1}
                     {:labels {"bar" "bar" "foo" "foo"}
                      :name   "s_sum"
                      :value  1.0}]
           :type    :summary}]
         (map without-created-sample (fc/collect))))

  (is (= (str "# HELP s sd\n"
              "# TYPE s summary\n"
              "s_count{foo=\"foo\",bar=\"baz\"} 1.0\n"
              "s_count{foo=\"foo\",bar=\"bar\"} 1.0\n"
              "s_sum{foo=\"foo\",bar=\"baz\"} 0.1\n"
              "s_sum{foo=\"foo\",bar=\"bar\"} 1.0\n")
         (-> (map without-created-sample (fc/collect)) (fc/serialize :text)))))

(deftest ^:integration test-histogram
  (fc/init! {:collectors {:h {:type :histogram, :help "hd"}}})
  (is (= [{:help    "hd"
           :name    "h"
           :samples [{:labels {"le" "0.005"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.01"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.025"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.05"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.075"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.1"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.25"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.5"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.75"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "1.0"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "2.5"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "5.0"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "7.5"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "10.0"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "+Inf"} :name "h_bucket" :value 0.0}
                     {:name "h_count" :value 0.0}
                     {:name "h_sum" :value 0.0}]
           :type    :histogram}]
         (map without-created-sample (fc/collect))))

  (fc/observe! :h 1.0)
  (is (= [{:help    "hd"
           :name    "h"
           :samples [{:labels {"le" "0.005"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.01"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.025"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.05"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.075"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.1"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.25"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.5"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.75"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "1.0"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "2.5"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "5.0"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "7.5"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "10.0"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "+Inf"} :name "h_bucket" :value 1.0}
                     {:name "h_count" :value 1.0}
                     {:name "h_sum" :value 1.0}]
           :type    :histogram}]
         (map without-created-sample (fc/collect))))

  (fc/observe! :h 0.1)
  (is (= [{:help    "hd"
           :name    "h"
           :samples [{:labels {"le" "0.005"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.01"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.025"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.05"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.075"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.1"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "0.25"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "0.5"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "0.75"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "1.0"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "2.5"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "5.0"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "7.5"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "10.0"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "+Inf"} :name "h_bucket" :value 2.0}
                     {:name "h_count" :value 2.0}
                     {:name "h_sum" :value 1.1}]
           :type    :histogram}]
         (map without-created-sample (fc/collect))))

  (fc/register! :h2 {:type :histogram :help "hd2"})
  (is (= [{:help    "hd"
           :name    "h"
           :samples [{:labels {"le" "0.005"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.01"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.025"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.05"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.075"} :name "h_bucket" :value 0.0}
                     {:labels {"le" "0.1"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "0.25"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "0.5"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "0.75"} :name "h_bucket" :value 1.0}
                     {:labels {"le" "1.0"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "2.5"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "5.0"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "7.5"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "10.0"} :name "h_bucket" :value 2.0}
                     {:labels {"le" "+Inf"} :name "h_bucket" :value 2.0}
                     {:name "h_count" :value 2.0}
                     {:name "h_sum" :value 1.1}]
           :type    :histogram}
          {:help    "hd2"
           :name    "h2"
           :samples [{:labels {"le" "0.005"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "0.01"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "0.025"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "0.05"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "0.075"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "0.1"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "0.25"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "0.5"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "0.75"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "1.0"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "2.5"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "5.0"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "7.5"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "10.0"} :name "h2_bucket" :value 0.0}
                     {:labels {"le" "+Inf"} :name "h2_bucket" :value 0.0}
                     {:name "h2_count" :value 0.0}
                     {:name "h2_sum" :value 0.0}]
           :type    :histogram}]
         (map without-created-sample (fc/collect))))

  (is (= (str "# HELP h hd\n"
              "# TYPE h histogram\n"
              "h_bucket{le=\"0.005\"} 0\n"
              "h_bucket{le=\"0.01\"} 0\n"
              "h_bucket{le=\"0.025\"} 0\n"
              "h_bucket{le=\"0.05\"} 0\n"
              "h_bucket{le=\"0.075\"} 0\n"
              "h_bucket{le=\"0.1\"} 1\n"
              "h_bucket{le=\"0.25\"} 1\n"
              "h_bucket{le=\"0.5\"} 1\n"
              "h_bucket{le=\"0.75\"} 1\n"
              "h_bucket{le=\"1\"} 2\n"
              "h_bucket{le=\"2.5\"} 2\n"
              "h_bucket{le=\"5\"} 2\n"
              "h_bucket{le=\"7.5\"} 2\n"
              "h_bucket{le=\"10\"} 2\n"
              "h_bucket{le=\"+Inf\"} 2\n"
              "h_count 2\n"
              "h_sum 1.1\n"
              "\n"
              "# HELP h2 hd2\n"
              "# TYPE h2 histogram\n"
              "h2_buckets{le=\"0.005\"} 0\n"
              "h2_buckets{le=\"0.01\"} 0\n"
              "h2_buckets{le=\"0.025\"} 0\n"
              "h2_buckets{le=\"0.05\"} 0\n"
              "h2_buckets{le=\"0.075\"} 0\n"
              "h2_buckets{le=\"0.1\"} 0\n"
              "h2_buckets{le=\"0.25\"} 0\n"
              "h2_buckets{le=\"0.5\"} 0\n"
              "h2_buckets{le=\"0.75\"} 0\n"
              "h2_buckets{le=\"1\"} 0\n"
              "h2_buckets{le=\"2.5\"} 0\n"
              "h2_buckets{le=\"5\"} 0\n"
              "h2_buckets{le=\"7.5\"} 0\n"
              "h2_buckets{le=\"10\"} 0\n"
              "h2_buckets{le=\"+Inf\"} 0\n"
              "h2_count 0\n"
              "h2_sum 0.0\n"
              (-> (fc/collect) (fc/serialize :text))))))

(deftest ^:integration test-histogram-with-labels
  (fc/init! {:collectors {:h {:type :histogram, :help "hd" :label-names [:foo :bar]}}})
  (is (= [{:help "hd" :name "h" :samples [] :type :histogram}]
         (fc/collect)))

  (fc/observe! :h 1.0 {:labels {:foo "foo" :bar "bar"}})
  (fc/observe! :h 0.1 {:labels {:foo "foo" :bar "baz"}})
  (is (= [{:help    "hd"
           :name    "h"
           :samples [{:labels {"bar" "baz" "foo" "foo" "le" "0.005"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "0.01"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "0.025"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "0.05"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "0.075"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.005"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.01"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.025"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.05"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.075"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.1"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.25"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.5"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "0.75"} :name "h_bucket" :value 0.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "0.1"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "0.25"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "0.5"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "0.75"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "1.0"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "2.5"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "5.0"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "7.5"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "10.0"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo" "le" "+Inf"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "1.0"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "2.5"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "5.0"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "7.5"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "10.0"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "bar" "foo" "foo" "le" "+Inf"} :name "h_bucket" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo"} :name "h_count" :value 1.0}
                     {:labels {"bar" "bar" "foo" "foo"} :name "h_count" :value 1.0}
                     {:labels {"bar" "baz" "foo" "foo"} :name "h_sum" :value 0.1}
                     {:labels {"bar" "bar" "foo" "foo"} :name "h_sum" :value 1.0}]
           :type    :histogram}]
         (map without-created-sample (fc/collect))))

  (is (= (str "# HELP h hd\n"
              "# TYPE h histogram\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.005\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.01\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.025\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.05\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.075\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.005\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.01\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.025\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.05\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.075\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.1\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.25\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.5\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"0.75\"} 0.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.1\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.25\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.5\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"0.75\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"1.0\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"2.5\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"5.0\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"7.5\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"10.0\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"baz\",le=\"+Inf\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"1.0\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"2.5\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"5.0\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"7.5\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"10.0\"} 1.0\n"
              "h_bucket{foo=\"foo\",bar=\"bar\",le=\"+Inf\"} 1.0\n"
              "h_count{foo=\"foo\",bar=\"baz\"} 1.0\n"
              "h_count{foo=\"foo\",bar=\"bar\"} 1.0\n"
              "h_sum{foo=\"foo\",bar=\"baz\"} 0.1\n"
              "h_sum{foo=\"foo\",bar=\"bar\"} 1.0\n")
         (-> (map without-created-sample (fc/collect)) (fc/serialize :text)))))

