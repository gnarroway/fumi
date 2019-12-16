(ns ^{:author "George Narroway"}
  fumi.collector.jvm
  "JVM collector"
  (:require [clojure.string :as string])
  (:import (java.lang.management ManagementFactory ThreadInfo ThreadMXBean MemoryMXBean MemoryUsage GarbageCollectorMXBean)))

(set! *warn-on-reflection* true)

(def ^ThreadMXBean thread-bean (ManagementFactory/getThreadMXBean))
(def ^MemoryMXBean memory-bean (ManagementFactory/getMemoryMXBean))
(def gc-beans (ManagementFactory/getGarbageCollectorMXBeans))

(defn- memory-by-area
  [heap non-heap f]
  [{:value (f heap) :labels {:area "heap"}}
   {:value (f non-heap) :labels {:area "non-heap"}}])

(defn collect
  "Returns a store of JVM related metrics."
  []
  (let [collect-memory (partial memory-by-area
                                (.getHeapMemoryUsage memory-bean)
                                (.getNonHeapMemoryUsage memory-bean))]
    [;;; Memory
     {:name    :jvm_memory_bytes_init
      :type    :gauge
      :help    "Initial bytes of a given JVM memory area."
      :samples (collect-memory (fn [^MemoryUsage mu] (.getInit mu)))}

     {:name    :jvm_memory_bytes_used
      :type    :gauge
      :help    "Used bytes of a given JVM memory area."
      :samples (collect-memory (fn [^MemoryUsage mu] (.getUsed mu)))}

     {:name    :jvm_memory_bytes_committed
      :type    :gauge
      :help    "Committed bytes of a given JVM memory area."
      :samples (collect-memory (fn [^MemoryUsage mu] (.getCommitted mu)))}

     {:name    :jvm_memory_bytes_max
      :type    :gauge
      :help    "Max bytes of a given JVM memory area."
      :samples (collect-memory (fn [^MemoryUsage mu] (.getMax mu)))}

     ;;; Threads
     {:name    :jvm_threads_current
      :type    :gauge
      :help    "Current thread count of a JVM."
      :samples [{:value (.getThreadCount thread-bean)}]}

     {:name    :jvm_threads_daemon
      :type    :gauge
      :help    "Daemon thread count of a JVM."
      :samples [{:value (.getDaemonThreadCount thread-bean)}]}

     {:name    :jvm_threads_peak
      :type    :gauge
      :help    "Peak thread count of a JVM."
      :samples [{:value (.getPeakThreadCount thread-bean)}]}

     {:name    :jvm_threads_started_total
      :type    :gauge
      :help    "Started thread count of a JVM."
      :samples [{:value (.getTotalStartedThreadCount thread-bean)}]}

     {:name    :jvm_threads_deadlocked
      :type    :gauge
      :help    "Cycles of JVM threads that are in deadlock waiting to acquire object monitors or ownable synchronizers."
      :samples [{:value (count (.findDeadlockedThreads thread-bean))}]}

     {:name    :jvm_threads_deadlocked_monitor
      :type    :gauge
      :help    "Cycles of JVM threads that are in deadlock waiting to acquire object monitors."
      :samples [{:value (count (.findMonitorDeadlockedThreads thread-bean))}]}

     {:name    :jvm_threads_state
      :type    :gauge
      :help    "Current count of threads by state."
      :samples (->>
                 (merge
                   (into {} (map (fn [^Thread$State t] [(.name t) 0]) (Thread$State/values)))
                   (frequencies
                     (map (fn [^ThreadInfo t] (-> t .getThreadState .name))
                          (.getThreadInfo ^ThreadMXBean thread-bean (.getAllThreadIds ^ThreadMXBean thread-bean) 0))))
                 (map (fn [[k v]] {:value v :labels {:state k}})))}

     ;;; GC
     {:name    :jvm_gc_collection_seconds
      :type    :summary
      :help    "Time spent in a given JVM garbage collector in seconds."
      :samples (mapcat (fn [^GarbageCollectorMXBean gc]
                         [{:name  (string/replace (str (.getName gc) "_count") #"\s" "_")
                           :value (.getCollectionCount gc)}]
                         [{:name  (string/replace (str (.getName gc) "_sum") #"\s" "_")
                           :value (double (/ (.getCollectionTime gc) 1000))}])
                       gc-beans)}

     ;;; Other
     {:name   :jvm_info
      :type   :gauge
      :help   "JVM version info"
      :sample [{:value  1
                :labels (->> ["java.runtime.version" "java.vm.vendor" "java.runtime.name"]
                             (map (juxt #(-> (string/split % #"\.") last) #(System/getProperty % "unknown")))
                             (into {}))}]}
     ]))

(comment
  (collect))