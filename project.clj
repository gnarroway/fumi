(defproject fumi "0.1.0-SNAPSHOT"
  :description "A Prometheus client for Clojure."
  :url "https://github.com/gnarroway/fumi"
  :license {:name         "The MIT License"
            :url          "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :deploy-repositories [["clojars" {:url           "https://clojars.org/repo"
                                    :username      :env/clojars_user
                                    :password      :env/clojars_pass
                                    :sign-releases false}]]
  :plugins [[lein-cljfmt "0.6.6"]]

  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]]}})
