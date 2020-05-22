(defproject com.walmartlabs/schematic "1.3.0"
  :description "A library to aid in assembling Systems for use with Component"
  :url "https://github.com/walmartlabs/schematic"
  :license {:name "Apache Software License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [com.stuartsierra/component "1.0.0"]]
  :plugins [[lein-codox "0.10.7"]
            [test2junit "1.2.5"]]
  :test2junit-output-dir ~(or (System/getenv "CIRCLE_TEST_REPORTS") "target/test2junit")
  :codox {:source-uri "https://github.com/walmartlabs/schematic/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}})
