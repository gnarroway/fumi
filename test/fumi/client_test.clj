(ns fumi.client-test
  (:require [clojure.test :refer :all]
            [fumi.client :as fc]))

(deftest test-registry
  (testing "validation"
    (is (thrown? AssertionError (fc/increase! :not_registered))))

  (testing "default"
    (let [r (fc/init! {:exclude-defaults? true
                       :collectors        {:c {:type :counter, :help "c2"}}})]
      (is (= 1 (count (fc/collect))) "collect without args uses default-registry")
      (is (= fc/default-registry r)))

    (let [_ (fc/init! {})
          names (->> (fc/collect) (map #(-> % :name name)))]
      (is (true? (some #(clojure.string/starts-with? % "jvm_") names)) "includes jvm metrics by default")
      (is (true? (some #(clojure.string/starts-with? % "process_") names)) "includes process metrics by default")))

  (testing "self-managed"
    (let [r (fc/init! {:self-managed?     true
                       :exclude-defaults? true
                       :collectors        {:c {:type :counter, :help "c1"}}})]
      (is (= 1 (count (fc/collect r))))
      (is (not= fc/default-registry r)))

    (let [r (fc/init! {:self-managed? true})
          names (->> (fc/collect r) (map #(-> % :name name)))]
      (is (true? (some #(clojure.string/starts-with? % "jvm_") names)) "includes jvm metrics by default")
      (is (true? (some #(clojure.string/starts-with? % "process_") names)) "includes process metrics by default"))))

(deftest test-custom-collector
  (let [r (fc/init! {:exclude-defaults? true
                     :collectors        {:c (reify fumi.collector/Collectable
                                              (-collect [_] {:help    "help"
                                                             :name    :c
                                                             :samples [{:name :c, :value 42}]
                                                             :type    :counter}))}})]
    (is (= [{:help    "help"
             :name    :c
             :samples [{:name :c :value 42}]
             :type    :counter}]
           (fc/collect r)))))

(deftest ^:integration test-counter
  (fc/init! {:exclude-defaults? true
             :collectors        {:c {:type :counter, :help "ch"}}})
  (is (= [{:help "ch" :name :c :samples [{:value 0}] :type :counter}]
         (fc/collect)))

  (fc/increase! :c)
  (is (= [{:help "ch" :name :c :samples [{:value 1}] :type :counter}]
         (fc/collect)))

  (fc/increase! :c {:n 1.1})
  (is (= [{:help "ch" :name :c :samples [{:value 2.1}] :type :counter}]
         (fc/collect)))

  (fc/register! :c2 {:type :counter :help "ch2"})
  (is (= [{:help "ch" :name :c :samples [{:value 2.1}] :type :counter}
          {:help "ch2" :name :c2 :samples [{:value 0}] :type :counter}]
         (fc/collect)))

  (is (= (str "# HELP c ch\n"
              "# HELP c counter\n"
              "c 2.1\n"
              "\n"
              "# HELP c2 ch2\n"
              "# HELP c2 counter\n"
              "c2 0\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-counter-with-labels
  (fc/init! {:exclude-defaults? true
             :collectors        {:c {:type :counter, :help "cd" :label-names [:foo :bar]}}})
  (is (= [{:help "cd" :name :c :samples [] :type :counter}]
         (fc/collect)))

  (fc/increase! :c {:labels {:foo "foo" :bar "bar"}})
  (fc/increase! :c {:n 1.1 :labels {:foo "foo" :bar "baz"}})
  (is (= [{:help    "cd"
           :name    :c
           :samples [{:labels {:bar "bar" :foo "foo"}
                      :value  1}
                     {:labels {:bar "baz" :foo "foo"}
                      :value  1.1}]
           :type    :counter}]
         (fc/collect)))

  (is (= (str "# HELP c cd\n"
              "# HELP c counter\n"
              "c{foo=\"foo\",bar=\"bar\"} 1\n"
              "c{foo=\"foo\",bar=\"baz\"} 1.1\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-gauge
  (fc/init! {:exclude-defaults? true
             :collectors        {:g {:type :gauge, :help "gh"}}})
  (is (= [{:help "gh" :name :g :samples [{:value 0}] :type :gauge}]
         (fc/collect)))

  (fc/increase! :g)
  (is (= [{:help "gh" :name :g :samples [{:value 1}] :type :gauge}]
         (fc/collect)))

  (fc/decrease! :g {:n 0.1})
  (is (= [{:help "gh" :name :g :samples [{:value 0.9}] :type :gauge}]
         (fc/collect)))

  (fc/set-n! :g 2.0)
  (is (= [{:help "gh" :name :g :samples [{:value 2.0}] :type :gauge}]
         (fc/collect)))

  (fc/register! :g2 {:type :gauge :help "gh2"})
  (is (= [{:help "gh" :name :g :samples [{:value 2.0}] :type :gauge}
          {:help "gh2" :name :g2 :samples [{:value 0}] :type :gauge}]
         (fc/collect)))

  (is (= (str "# HELP g gh\n"
              "# HELP g gauge\n"
              "g 2.0\n"
              "\n"
              "# HELP g2 gh2\n"
              "# HELP g2 gauge\n"
              "g2 0\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-gauge-with-labels
  (fc/init! {:exclude-defaults? true
             :collectors        {:g {:type :gauge, :help "gd" :label-names [:foo :bar]}}})
  (is (= [{:help "gd" :name :g :samples [] :type :gauge}]
         (fc/collect)))

  (fc/increase! :g {:labels {:foo "foo" :bar "bar"}})
  (fc/increase! :g {:n 1.1 :labels {:foo "foo" :bar "baz"}})
  (fc/set-n! :g 2 {:labels {:foo "foo" :bar "bam"}})
  (is (= [{:help    "gd"
           :name    :g
           :samples [{:labels {:bar "bar" :foo "foo"}
                      :value  1}
                     {:labels {:bar "baz" :foo "foo"}
                      :value  1.1}
                     {:labels {:bar "bam" :foo "foo"}
                      :value  2}]
           :type    :gauge}]
         (fc/collect)))

  (is (= (str "# HELP g gd\n"
              "# HELP g gauge\n"
              "g{foo=\"foo\",bar=\"bar\"} 1\n"
              "g{foo=\"foo\",bar=\"baz\"} 1.1\n"
              "g{foo=\"foo\",bar=\"bam\"} 2\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-summary
  (fc/init! {:exclude-defaults? true
             :collectors        {:s {:type :summary, :help "sd"}}})
  (is (= [{:help "sd" :name :s :samples [] :type :summary}]
         (fc/collect)))

  (fc/observe! :s 1.0)
  (is (= [{:help    "sd"
           :name    :s
           :samples [{:name "s_count" :value 1}
                     {:name "s_sum" :value 1.0}]
           :type    :summary}]
         (fc/collect)))

  (fc/observe! :s 0.1)
  (is (= [{:help    "sd"
           :name    :s
           :samples [{:name "s_count" :value 2}
                     {:name "s_sum" :value 1.1}]
           :type    :summary}]
         (fc/collect)))

  (fc/register! :s2 {:type :summary :help "sd2"})
  (is (= [{:help    "sd"
           :name    :s
           :samples [{:name "s_count" :value 2}
                     {:name "s_sum" :value 1.1}]
           :type    :summary}
          {:help    "sd2"
           :name    :s2
           :samples []
           :type    :summary}]
         (fc/collect)))

  (is (= (str "# HELP s sd\n"
              "# HELP s summary\n"
              "s_count 2\n"
              "s_sum 1.1\n"
              "\n"
              "# HELP s2 sd2\n"
              "# HELP s2 summary\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-summary-with-labels
  (fc/init! {:exclude-defaults? true
             :collectors        {:s {:type :summary, :help "sd" :label-names [:foo :bar]}}})
  (is (= [{:help "sd" :name :s :samples [] :type :summary}]
         (fc/collect)))

  (fc/observe! :s 1.0 {:labels {:foo "foo" :bar "bar"}})
  (fc/observe! :s 0.1 {:labels {:foo "foo" :bar "baz"}})
  (is (= [{:help    "sd"
           :name    :s
           :samples [{:labels {:bar "bar" :foo "foo"}
                      :name   "s_count"
                      :value  1}
                     {:labels {:bar "bar" :foo "foo"}
                      :name   "s_sum"
                      :value  1.0}
                     {:labels {:bar "baz" :foo "foo"}
                      :name   "s_count"
                      :value  1}
                     {:labels {:bar "baz" :foo "foo"}
                      :name   "s_sum"
                      :value  0.1}]
           :type    :summary}]
         (fc/collect)))

  (is (= (str "# HELP s sd\n"
              "# HELP s summary\n"
              "s_count{foo=\"foo\",bar=\"bar\"} 1\n"
              "s_sum{foo=\"foo\",bar=\"bar\"} 1.0\n"
              "s_count{foo=\"foo\",bar=\"baz\"} 1\n"
              "s_sum{foo=\"foo\",bar=\"baz\"} 0.1\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-histogram
  (fc/init! {:exclude-defaults? true
             :collectors        {:h {:type :histogram, :help "hd"}}})
  (is (= [{:help "hd" :name :h :samples [] :type :histogram}]
         (fc/collect)))

  (fc/observe! :h 1.0)
  (is (= [{:help    "hd"
           :name    :h
           :samples [{:labels {:le "0.005"} :name "h_buckets" :value 0}
                     {:labels {:le "0.01"} :name "h_buckets" :value 0}
                     {:labels {:le "0.025"} :name "h_buckets" :value 0}
                     {:labels {:le "0.05"} :name "h_buckets" :value 0}
                     {:labels {:le "0.075"} :name "h_buckets" :value 0}
                     {:labels {:le "0.1"} :name "h_buckets" :value 0}
                     {:labels {:le "0.25"} :name "h_buckets" :value 0}
                     {:labels {:le "0.5"} :name "h_buckets" :value 0}
                     {:labels {:le "0.75"} :name "h_buckets" :value 0}
                     {:labels {:le "1"} :name "h_buckets" :value 1}
                     {:labels {:le "2.5"} :name "h_buckets" :value 1}
                     {:labels {:le "5"} :name "h_buckets" :value 1}
                     {:labels {:le "7.5"} :name "h_buckets" :value 1}
                     {:labels {:le "10"} :name "h_buckets" :value 1}
                     {:labels {:le "+Inf"} :name "h_buckets" :value 1}
                     {:name "h_count" :value 1}
                     {:name "h_sum" :value 1.0}]
           :type    :histogram}]
         (fc/collect)))

  (fc/observe! :h 0.1)
  (is (= [{:help    "hd"
           :name    :h
           :samples [{:labels {:le "0.005"} :name "h_buckets" :value 0}
                     {:labels {:le "0.01"} :name "h_buckets" :value 0}
                     {:labels {:le "0.025"} :name "h_buckets" :value 0}
                     {:labels {:le "0.05"} :name "h_buckets" :value 0}
                     {:labels {:le "0.075"} :name "h_buckets" :value 0}
                     {:labels {:le "0.1"} :name "h_buckets" :value 1}
                     {:labels {:le "0.25"} :name "h_buckets" :value 1}
                     {:labels {:le "0.5"} :name "h_buckets" :value 1}
                     {:labels {:le "0.75"} :name "h_buckets" :value 1}
                     {:labels {:le "1"} :name "h_buckets" :value 2}
                     {:labels {:le "2.5"} :name "h_buckets" :value 2}
                     {:labels {:le "5"} :name "h_buckets" :value 2}
                     {:labels {:le "7.5"} :name "h_buckets" :value 2}
                     {:labels {:le "10"} :name "h_buckets" :value 2}
                     {:labels {:le "+Inf"} :name "h_buckets" :value 2}
                     {:name "h_count" :value 2}
                     {:name "h_sum" :value 1.1}]
           :type    :histogram}]
         (fc/collect)))

  (fc/register! :h2 {:type :histogram :help "hd2"})
  (is (= [{:help    "hd"
           :name    :h
           :samples [{:labels {:le "0.005"} :name "h_buckets" :value 0}
                     {:labels {:le "0.01"} :name "h_buckets" :value 0}
                     {:labels {:le "0.025"} :name "h_buckets" :value 0}
                     {:labels {:le "0.05"} :name "h_buckets" :value 0}
                     {:labels {:le "0.075"} :name "h_buckets" :value 0}
                     {:labels {:le "0.1"} :name "h_buckets" :value 1}
                     {:labels {:le "0.25"} :name "h_buckets" :value 1}
                     {:labels {:le "0.5"} :name "h_buckets" :value 1}
                     {:labels {:le "0.75"} :name "h_buckets" :value 1}
                     {:labels {:le "1"} :name "h_buckets" :value 2}
                     {:labels {:le "2.5"} :name "h_buckets" :value 2}
                     {:labels {:le "5"} :name "h_buckets" :value 2}
                     {:labels {:le "7.5"} :name "h_buckets" :value 2}
                     {:labels {:le "10"} :name "h_buckets" :value 2}
                     {:labels {:le "+Inf"} :name "h_buckets" :value 2}
                     {:name "h_count" :value 2}
                     {:name "h_sum" :value 1.1}]
           :type    :histogram}
          {:help    "hd2"
           :name    :h2
           :samples []
           :type    :histogram}]
         (fc/collect)))

  (is (= (str "# HELP h hd\n"
              "# HELP h histogram\n"
              "h_buckets{le=\"0.005\"} 0\n"
              "h_buckets{le=\"0.01\"} 0\n"
              "h_buckets{le=\"0.025\"} 0\n"
              "h_buckets{le=\"0.05\"} 0\n"
              "h_buckets{le=\"0.075\"} 0\n"
              "h_buckets{le=\"0.1\"} 1\n"
              "h_buckets{le=\"0.25\"} 1\n"
              "h_buckets{le=\"0.5\"} 1\n"
              "h_buckets{le=\"0.75\"} 1\n"
              "h_buckets{le=\"1\"} 2\n"
              "h_buckets{le=\"2.5\"} 2\n"
              "h_buckets{le=\"5\"} 2\n"
              "h_buckets{le=\"7.5\"} 2\n"
              "h_buckets{le=\"10\"} 2\n"
              "h_buckets{le=\"+Inf\"} 2\n"
              "h_count 2\n"
              "h_sum 1.1\n"
              "\n"
              "# HELP h2 hd2\n"
              "# HELP h2 histogram\n")
         (-> (fc/collect) (fc/serialize :text)))))

(deftest ^:integration test-histogram-with-labels
  (fc/init! {:exclude-defaults? true
             :collectors        {:h {:type :histogram, :help "hd" :label-names [:foo :bar]}}})
  (is (= [{:help "hd" :name :h :samples [] :type :histogram}]
         (fc/collect)))

  (fc/observe! :h 1.0 {:labels {:foo "foo" :bar "bar"}})
  (fc/observe! :h 0.1 {:labels {:foo "foo" :bar "baz"}})
  (is (= [{:help    "hd"
           :name    :h
           :samples [{:labels {:bar "bar" :foo "foo" :le "0.005"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "0.01"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "0.025"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "0.05"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "0.075"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "0.1"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "0.25"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "0.5"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "0.75"} :name "h_buckets" :value 0}
                     {:labels {:bar "bar" :foo "foo" :le "1"} :name "h_buckets" :value 1}
                     {:labels {:bar "bar" :foo "foo" :le "2.5"} :name "h_buckets" :value 1}
                     {:labels {:bar "bar" :foo "foo" :le "5"} :name "h_buckets" :value 1}
                     {:labels {:bar "bar" :foo "foo" :le "7.5"} :name "h_buckets" :value 1}
                     {:labels {:bar "bar" :foo "foo" :le "10"} :name "h_buckets" :value 1}
                     {:labels {:bar "bar" :foo "foo" :le "+Inf"} :name "h_buckets" :value 1}
                     {:labels {:bar "bar" :foo "foo"} :name "h_count" :value 1}
                     {:labels {:bar "bar" :foo "foo"} :name "h_sum" :value 1.0}
                     {:labels {:bar "baz" :foo "foo" :le "0.005"} :name "h_buckets" :value 0}
                     {:labels {:bar "baz" :foo "foo" :le "0.01"} :name "h_buckets" :value 0}
                     {:labels {:bar "baz" :foo "foo" :le "0.025"} :name "h_buckets" :value 0}
                     {:labels {:bar "baz" :foo "foo" :le "0.05"} :name "h_buckets" :value 0}
                     {:labels {:bar "baz" :foo "foo" :le "0.075"} :name "h_buckets" :value 0}
                     {:labels {:bar "baz" :foo "foo" :le "0.1"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "0.25"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "0.5"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "0.75"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "1"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "2.5"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "5"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "7.5"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "10"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo" :le "+Inf"} :name "h_buckets" :value 1}
                     {:labels {:bar "baz" :foo "foo"} :name "h_count" :value 1}
                     {:labels {:bar "baz" :foo "foo"} :name "h_sum" :value 0.1}]
           :type    :histogram}]
         (fc/collect)))

  (is (= (str "# HELP h hd\n"
              "# HELP h histogram\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.005\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.01\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.025\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.05\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.075\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.1\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.25\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.5\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"0.75\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"1\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"2.5\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"5\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"7.5\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"10\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"bar\",le=\"+Inf\"} 1\n"
              "h_count{foo=\"foo\",bar=\"bar\"} 1\n"
              "h_sum{foo=\"foo\",bar=\"bar\"} 1.0\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.005\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.01\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.025\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.05\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.075\"} 0\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.1\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.25\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.5\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"0.75\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"1\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"2.5\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"5\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"7.5\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"10\"} 1\n"
              "h_buckets{foo=\"foo\",bar=\"baz\",le=\"+Inf\"} 1\n"
              "h_count{foo=\"foo\",bar=\"baz\"} 1\n"
              "h_sum{foo=\"foo\",bar=\"baz\"} 0.1\n")
         (-> (fc/collect) (fc/serialize :text)))))

