(defproject fumi "0.3.1"
  :description "A Prometheus client for Clojure."
  :url "https://github.com/gnarroway/fumi"
  :license {:name         "The MIT License"
            :url          "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :deploy-repositories [["clojars" {:url           "https://clojars.org/repo"
                                    :username      :env/clojars_user
                                    :password      :env/clojars_pass
                                    :sign-releases false}]]
  :dependencies [[io.prometheus/simpleclient "0.12.0"]
                 [io.prometheus/simpleclient_common "0.12.0"]]
  :plugins [[lein-cljfmt "0.6.6"]]

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  [io.prometheus/simpleclient_hotspot "0.12.0"]]}})
