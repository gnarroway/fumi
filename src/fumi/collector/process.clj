(ns ^{:author "George Narroway"}
 fumi.collector.process
  "Process collector"
  (:require [clojure.string :as string])
  (:import (java.lang.management ManagementFactory)))

(def ^:const milliseconds-per-second 1000.0)
(def ^:const nanoseconds-per-second 1e9)
(def ^:const kb 1024)

(def os-bean (ManagementFactory/getOperatingSystemMXBean))
(def runtime-bean (ManagementFactory/getRuntimeMXBean))

(defn- from-val
  [v]
  (when v [{:value v}]))

(defn- memory-stat
  "Extract stat from the content of /proc/self/status

  `stats` is a list of string tuples e.g. [[VMRSS:, 36, kb]]
  `field` is the content of the first tuple e.g. VMRSS:"
  [stats field]
  (some->> stats
           (filter #(= field (first %)))
           (first)
           (second)
           (Integer/parseInt)
           (* kb)))

(defn collect
  "Returns process related metrics."
  []
  (let [proc-status (try
                      (->> (slurp "/proc/self/status")
                           (string/split-lines)
                           (map #(string/split % #"\s+")))
                      (catch Throwable _
                        ; Expected for non-linux
                        nil))]
    [{:name    :process_cpu_seconds_total
      :type    :counter
      :help    "Total user and system CPU time spent in seconds."
      :samples (-> (.getProcessCpuTime os-bean)
                   (/ nanoseconds-per-second)
                   (from-val))}

     {:name    :process_start_time_seconds
      :type    :gauge
      :help    "Start time of the process since unix epoch in seconds."
      :samples (-> (.getStartTime runtime-bean)
                   (/ milliseconds-per-second)
                   (from-val))}

     {:name    :process_open_fds
      :type    :gauge
      :help    "Number of open file descriptors."
      :samples (-> (try
                     (.getOpenFileDescriptorCount os-bean)
                     (catch Throwable _
                       ; Expected for non-linux
                       nil))
                   (from-val))}

     {:name    :process_open_fds
      :type    :gauge
      :help    "Maximum number of open file descriptors."
      :samples (-> (try
                     (.getMaxFileDescriptorCount os-bean)
                     (catch Throwable _
                       ; Expected for non-linux
                       nil))
                   (from-val))}

     {:name    :process_resident_memory_bytes
      :type    :gauge
      :help    "Resident memory size in bytes."
      :samples (from-val (memory-stat proc-status "VmRSS:"))}

     {:name    :process_virtual_memory_bytes
      :type    :gauge
      :help    "Virtual memory size in bytes."
      :samples (from-val (memory-stat proc-status "VmSize:"))}]))

(comment
  (collect))