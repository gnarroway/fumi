(ns fumi.collector-test
  (:require [clojure.test :refer :all]
            [fumi.collector :refer :all]))

(deftest test-counter
  (testing "without labels"
    (let [c #(counter :c {:help "test"})]

      (is (= {:help    "test"
              :name    :c
              :samples [{:value 0.0}]
              :type    :counter}
             (-> (c) (-collect))) "default to 0")

      (is (= {:help    "test"
              :name    :c
              :samples [{:value 1.0}]
              :type    :counter}
             (-> (c) (increase {}) (-collect))))

      (is (= {:help    "test"
              :name    :c
              :samples [{:value 2.1}]
              :type    :counter}
             (-> (c) (increase {:n 2.1}) (-collect))))

      (is (thrown? AssertionError (increase (c) {:n 0})) "n must be positive")
      (is (thrown? Exception (decrease (c) {})) "counter cannot decrease")))

  (testing "with labels"
    (let [c #(counter :c {:help "test" :label-names [:foo]})]

      (is (= {:help    "test"
              :name    :c
              :samples []
              :type    :counter}
             (-> (c) (-collect))))

      (is (= {:help    "test"
              :name    :c
              :samples [{:labels {:foo "bar"} :value 1.0}
                        {:labels {:foo "bob"} :value 1.1}]
              :type    :counter}
             (-> (c)
                 (-prepare {:labels {:foo "bar"}})
                 (increase {:labels {:foo "bar"}})
                 (-prepare {:labels {:foo "bob"}})
                 (increase {:labels {:foo "bob"}})
                 (-prepare {:labels {:foo "bob"}})
                 (increase {:labels {:foo "bob"} :n 0.1})
                 (-collect))))

      (is (thrown? AssertionError (increase (c) {:n 0})) "labels must be provided for all label-names")
      (is (thrown? AssertionError (increase (c) {:n 0 :labels {:foo "bar" :a "b"}})) "labels must be provided for only label-names"))))

(deftest test-gauge
  (testing "without labels"
    (let [g #(gauge :g {:help "test"})]

      (is (= {:help    "test"
              :name    :g
              :samples [{:value 0.0}]
              :type    :gauge}
             (-> (g) (-collect))) "defaults to 0")

      (is (= {:help    "test"
              :name    :g
              :samples [{:value 1.0}]
              :type    :gauge}
             (-> (g) (increase {}) (-collect))))

      (is (= {:help    "test"
              :name    :g
              :samples [{:value -1.0}]
              :type    :gauge}
             (-> (g) (decrease {}) (-collect))))

      (is (= {:help    "test"
              :name    :g
              :samples [{:value -1.0}]
              :type    :gauge}
             (-> (g) (increase {:n 2.1}) (decrease {:n 3.1}) (-collect))))

      (is (= {:help    "test"
              :name    :g
              :samples [{:value 2.1}]
              :type    :gauge}
             (-> (g) (set-n 2.1 {}) (-collect))))

      (is (thrown? AssertionError (increase (g) {:n 0})) "n must be positive")
      (is (thrown? AssertionError (decrease (g) {:n 0})) "n must be positive")))

  (testing "with labels"
    (let [g #(gauge :g {:help "test" :label-names [:foo]})]

      (is (= {:help    "test"
              :name    :g
              :samples []
              :type    :gauge}
             (-> (g) (-collect))))

      (is (= {:help    "test"
              :name    :g
              :samples [{:labels {:foo "bar"} :value 1.0}
                        {:labels {:foo "bob"} :value 1.1}]
              :type    :gauge}
             (-> (g)
                 (-prepare {:labels {:foo "bar"}})
                 (increase {:labels {:foo "bar"}})
                 (-prepare {:labels {:foo "bob"}})
                 (increase {:labels {:foo "bob"}})
                 (-prepare {:labels {:foo "bob"}})
                 (increase {:labels {:foo "bob"} :n 0.1})
                 (-collect))))

      (is (= {:help    "test"
              :name    :g
              :samples [{:labels {:foo "bar"} :value 1.0}
                        {:labels {:foo "bob"} :value -0.9}]
              :type    :gauge}
             (-> (g)
                 (-prepare {:labels {:foo "bar"}})
                 (increase {:labels {:foo "bar"}})
                 (-prepare {:labels {:foo "bob"}})
                 (decrease {:labels {:foo "bob"}})
                 (-prepare {:labels {:foo "bob"}})
                 (increase {:labels {:foo "bob"} :n 0.1})
                 (-collect))))

      (is (thrown? AssertionError (increase (g) {:n 0})) "labels must be provided for all label-names")
      (is (thrown? AssertionError (increase (g) {:n 0 :labels {:foo "bar" :a "b"}})) "labels must be provided for only label-names"))))

(deftest test-summary
  (testing "without labels"
    (let [s #(summary :s {:help "test"})]
      (is (= {:help    "test"
              :name    :s
              :samples [{:name  "s_count"
                         :value 0}
                        {:name  "s_sum"
                         :value 0.0}]
              :type    :summary}
             (-collect (s))))

      (is (= {:help    "test"
              :name    :s
              :samples [{:name "s_count" :value 2}
                        {:name "s_sum" :value 3.1}]
              :type    :summary}
             (-> (s)
                 (observe 1 {})
                 (observe 2.1 {})
                 (-collect))))))

  (testing "with labels"
    (let [s #(summary :s {:help "test" :label-names [:foo]})]
      (is (= {:help    "test"
              :name    :s
              :samples []
              :type    :summary}
             (-collect (s))))

      (is (= {:help    "test"
              :name    :s
              :samples [{:labels {:foo "bar"} :name "s_count" :value 1}
                        {:labels {:foo "bar"} :name "s_sum" :value 1.0}
                        {:labels {:foo "baz"} :name "s_count" :value 2}
                        {:labels {:foo "baz"} :name "s_sum" :value 2.1}]
              :type    :summary}
             (-> (s)
                 (prepare {:labels {:foo "bar"}})
                 (observe 1 {:labels {:foo "bar"}})
                 (prepare {:labels {:foo "baz"}})
                 (observe 2 {:labels {:foo "baz"}})
                 (prepare {:labels {:foo "baz"}})
                 (observe 0.1 {:labels {:foo "baz"}})
                 (-collect))))

      (is (= {:help    "test"
              :name    :s
              :samples [{:labels {:foo "bar"} :name "s_count" :value 1}
                        {:labels {:foo "bar"} :name "s_sum" :value 1.0}
                        {:labels {:foo "baz"} :name "s_count" :value 2}
                        {:labels {:foo "baz"} :name "s_sum" :value 2.1}]
              :type    :summary}
             (-> (s)
                 (prepare {:labels {:foo "bar"}})
                 (observe 1 {:labels {:foo "bar"}})
                 (prepare {:labels {:foo "baz"}})
                 (observe 2 {:labels {:foo "baz"}})
                 (prepare {:labels {:foo "baz"}})
                 (observe 0.1 {:labels {:foo "baz"}})
                 (-collect))))

      (is (thrown? AssertionError (observe (s) 1 {})) "labels must be provided for all label-names")
      (is (thrown? AssertionError (observe (s) 1 {:labels {:foo "bar" :a "b"}})) "labels must be provided for only label-names"))))

(deftest test-histogram
  (testing "without labels"
    (let [h #(histogram :h {:help "test"})]
      (is (= {:help    "test"
              :name    :h
              :samples [{:labels {:le "0.005"} :name   "h_buckets" :value  0}
                        {:labels {:le "0.01"} :name   "h_buckets" :value  0}
                        {:labels {:le "0.025"} :name   "h_buckets" :value  0}
                        {:labels {:le "0.05"} :name   "h_buckets" :value  0}
                        {:labels {:le "0.075"} :name   "h_buckets" :value  0}
                        {:labels {:le "0.1"} :name   "h_buckets" :value  0}
                        {:labels {:le "0.25"} :name   "h_buckets" :value  0}
                        {:labels {:le "0.5"} :name   "h_buckets" :value  0}
                        {:labels {:le "0.75"} :name   "h_buckets" :value  0}
                        {:labels {:le "1"} :name   "h_buckets" :value  0}
                        {:labels {:le "2.5"} :name   "h_buckets" :value  0}
                        {:labels {:le "5"} :name   "h_buckets" :value  0}
                        {:labels {:le "7.5"} :name   "h_buckets" :value  0}
                        {:labels {:le "10"} :name   "h_buckets" :value  0}
                        {:labels {:le "+Inf"} :name   "h_buckets" :value  0}
                        {:name  "h_count" :value 0}
                        {:name  "h_sum" :value 0.0}]
              :type    :histogram}
             (-collect (h))))

      (is (= {:help    "test"
              :name    :h
              :samples [{:labels {:le "0.005"} :name "h_buckets" :value 0}
                        {:labels {:le "0.01"} :name "h_buckets" :value 1}
                        {:labels {:le "0.025"} :name "h_buckets" :value 1}
                        {:labels {:le "0.05"} :name "h_buckets" :value 1}
                        {:labels {:le "0.075"} :name "h_buckets" :value 1}
                        {:labels {:le "0.1"} :name "h_buckets" :value 1}
                        {:labels {:le "0.25"} :name "h_buckets" :value 1}
                        {:labels {:le "0.5"} :name "h_buckets" :value 2}
                        {:labels {:le "0.75"} :name "h_buckets" :value 2}
                        {:labels {:le "1"} :name "h_buckets" :value 2}
                        {:labels {:le "2.5"} :name "h_buckets" :value 2}
                        {:labels {:le "5"} :name "h_buckets" :value 2}
                        {:labels {:le "7.5"} :name "h_buckets" :value 3}
                        {:labels {:le "10"} :name "h_buckets" :value 3}
                        {:labels {:le "+Inf"} :name "h_buckets" :value 3}
                        {:name "h_count" :value 3}
                        {:name "h_sum" :value 7.51}]
              :type    :histogram}
             (-> (h)
                 (observe 0.5 {})
                 (observe 0.01 {})
                 (observe 7 {})
                 (-collect))))))

  (testing "with labels"
    (let [h #(histogram :h {:help "test" :label-names [:path]})]
      (is (= {:help    "test"
              :name    :h
              :samples []
              :type    :histogram}
             (-collect (h))))

      (is (= {:help    "test"
              :name    :h
              :samples [{:labels {:le "0.005" :path "/foo"} :name "h_buckets" :value 0}
                        {:labels {:le "0.01" :path "/foo"} :name "h_buckets" :value 0}
                        {:labels {:le "0.025" :path "/foo"} :name "h_buckets" :value 0}
                        {:labels {:le "0.05" :path "/foo"} :name "h_buckets" :value 0}
                        {:labels {:le "0.075" :path "/foo"} :name "h_buckets" :value 0}
                        {:labels {:le "0.1" :path "/foo"} :name "h_buckets" :value 0}
                        {:labels {:le "0.25" :path "/foo"} :name "h_buckets" :value 0}
                        {:labels {:le "0.5" :path "/foo"} :name "h_buckets" :value 1}
                        {:labels {:le "0.75" :path "/foo"} :name "h_buckets" :value 1}
                        {:labels {:le "1" :path "/foo"} :name "h_buckets" :value 1}
                        {:labels {:le "2.5" :path "/foo"} :name "h_buckets" :value 1}
                        {:labels {:le "5" :path "/foo"} :name "h_buckets" :value 1}
                        {:labels {:le "7.5" :path "/foo"} :name "h_buckets" :value 2}
                        {:labels {:le "10" :path "/foo"} :name "h_buckets" :value 2}
                        {:labels {:le "+Inf" :path "/foo"} :name "h_buckets" :value 2}
                        {:labels {:path "/foo"} :name "h_count" :value 2}
                        {:labels {:path "/foo"} :name "h_sum" :value 7.5}
                        {:labels {:le "0.005" :path "/bar"} :name "h_buckets" :value 0}
                        {:labels {:le "0.01" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "0.025" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "0.05" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "0.075" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "0.1" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "0.25" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "0.5" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "0.75" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "1" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "2.5" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "5" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "7.5" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "10" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:le "+Inf" :path "/bar"} :name "h_buckets" :value 1}
                        {:labels {:path "/bar"} :name "h_count" :value 1}
                        {:labels {:path "/bar"} :name "h_sum" :value 0.01}]
              :type    :histogram}
             (-> (h)
                 (prepare {:labels {:path "/foo"}})
                 (observe 0.5 {:labels {:path "/foo"}})
                 (prepare {:labels {:path "/bar"}})
                 (observe 0.01 {:labels {:path "/bar"}})
                 (prepare {:labels {:path "/foo"}})
                 (observe 7 {:labels {:path "/foo"}})
                 (-collect))))

      (is (thrown? AssertionError (observe (h) 1 {})) "labels must be provided for all label-names")
      (is (thrown? AssertionError (observe (h) 1 {:labels {:foo "bar" :a "b"}})) "labels must be provided for only label-names"))))