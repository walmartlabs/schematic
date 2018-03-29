;;; Copyright (c) 2017-present, Walmart Inc.
;;;
;;; Licensed under the Apache License, Version 2.0 (the "License");
;;; you may not use this file except in compliance with the License.
;;; You may obtain a copy of the License at
;;;
;;; http://www.apache.org/licenses/LICENSE-2.0
;;;
;;; Unless required by applicable law or agreed to in writing, software
;;; distributed under the License is distributed on an "AS IS" BASIS,
;;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;; See the License for the specific language governing permissions and
;;; limitations under the License.

(ns com.walmartlabs.schematic-test
  (:require [clojure.test :refer :all]
            [clojure.pprint]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.schematic :as sc])
  (:import (clojure.lang ExceptionInfo)
           (java.io FileNotFoundException)))

(defrecord TestRecord [a b c]
  component/Lifecycle
  (start [this]
    (assoc this :c (str a "-" b)))
  (stop [this]
    (assoc this :c nil)))

(defn create-record [m]
  (map->TestRecord m))

(deftest normalizing-merge-defs
  (testing "normalizing merge-defs"
    (are [expected actual] (= expected actual)
      {:to [] :from [:foo] :select :all}
      (sc/normalize-merge-def :foo)

      {:to [] :from [:foo] :select {:one :one}}
      (sc/normalize-merge-def {:from :foo :select [:one]})

      {:to [] :from [:foo] :select {:host :hostname}}
      (sc/normalize-merge-def {:from :foo :select {:host :hostname}}))))

(deftest evaluating-merge-defs
  (testing "evaluting successful merge-defs"
    (are [expected actual] (= expected actual)
      {:bar 3}
      (sc/merge-value {} :dst {:from [:foo] :select [:bar]} {:foo {:bar 3}})

      {:existing 5 :bar 3 :baz 4}
      (sc/merge-value {:existing 5} :dst {:from [:foo] :select [:bar :baz]} {:foo {:bar 3 :baz 4}})

      {:bar 3 :baz 4}
      (sc/merge-value {} :dst {:from [:foo] :select :all} {:foo {:bar 3 :baz 4}})

      {:new-bar 3}
      (sc/merge-value {} :dst {:from [:foo] :select {:new-bar :bar}} {:foo {:bar 3 :baz 4}})

      {:existing {:conn {:host "localhost" :port 1234}}}
      (sc/merge-value {:existing {:conn {:host "localhost"}}} :dst {:to [:existing :conn] :from [:conn-details]} {:conn-details {:port 1234}})
      ))

  (testing "evaluting invalid merge-defs"
    (are [expr error] (= (meta expr) error)
      ;; selecting keys from non-map
      (sc/merge-value {} :dst {:from [:foo] :select [:baz]} {:foo 3})
      {:com.walmartlabs.schematic/merge-errors {:non-map-src {:dst [{:from [:foo]
                                                                     :select {:baz :baz}
                                                                     :to []}]}}}

      ;; selecting keys from nested non-map
      (sc/merge-value {} :dst {:from [:foo :bar] :select [:baz]} {:foo {:bar 3}})
      {:com.walmartlabs.schematic/merge-errors {:non-map-src {:dst [{:from [:foo
                                                                            :bar]
                                                                     :select {:baz :baz}
                                                                     :to []}]}}}

      ;; attempting to merge non-map value :bar into root config object
      (sc/merge-value {} :dst {:to [] :from [:foo :bar] :select :all} {:foo {:bar 3}})
      {:com.walmartlabs.schematic/merge-errors {:non-map-src {:dst [{:from [:foo
                                                                            :bar]
                                                                     :select :all
                                                                     :to []}]}}})))

(deftest reference-injection
  (testing "on system start, references (via :sc/refs config) are correctly injected"
    (let [config {:app {:sc/refs {:host :host
                                  :port :port
                                  :api-keys :api-keys}}
                  :host "localhost"
                  :port 1234
                  :api-keys [1 2 3 4]}]
      (is (= {:host "localhost"
              :port 1234
              :api-keys [1 2 3 4]}
             (-> (sc/assemble-system config)
                 (component/start)
                 :app)))))
  (testing "on system start, references (via :sc/refs config as vectors) are correctly injected"
    (let [config {:app {:sc/refs [:host :port :api-keys]}
                  :host "localhost"
                  :port 1234
                  :api-keys [1 2 3 4]}]
      (is (= {:host "localhost"
              :port 1234
              :api-keys [1 2 3 4]}
             (-> (sc/assemble-system config)
                 (component/start)
                 :app))))))

(deftest resolve-component-from-symbol
  (testing "config reference to a defrecord is correctly constructed"
    (let [config {:record {:sc/create-fn 'com.walmartlabs.schematic-test/map->TestRecord
                           :a "A"
                           :sc/refs {:b :b-obj}}
                  :b-obj "B"}]
      (is (= {:a "A"
              :b "B"
              :c "A-B"}
             (->> (sc/assemble-system config)
                  (component/start)
                  :record
                  (into {}))))))

  (testing "config reference to a function which accepts a map is correctly constructed"
    (let [config {:record {:sc/create-fn 'com.walmartlabs.schematic-test/create-record
                           :a "A"
                           :sc/refs {:b :b-obj}}
                  :b-obj "B"}]
      (is (= {:a "A"
              :b "B"
              :c "A-B"}
             (->> (sc/assemble-system config)
                  (component/start)
                  :record
                  (into {})))))))

(deftest assemble-subsystem
  (testing "only the necessary components from the config are included in the final system"
    (let [config {:a {:sc/refs {:b :b-obj}}
                  :b-obj {:sc/refs {:c :c-obj}}
                  :c-obj {:id "C"}
                  :unused-1 {:foo :bar
                             :sc/refs {:other :unused-d}}
                  :unused-2 {:blah :blah}}]
      (is (= {:a {:b {:c {:id "C"}}}
              :b-obj {:c {:id "C"}}
              :c-obj {:id "C"}}
             (->> (sc/assemble-system config [:a])
                  (component/start)
                  (into {}))))))

  (testing "missing keys for included parts of the total system throw an exception"
    (let [config {:a {:sc/refs {:b :b-obj}}
                  :b-obj {:sc/refs {:c :c-obj
                                    :missing :missing-ref}}
                  :unused-1 {:foo :bar
                             :sc/refs {:other :unused-2
                                       :missing :missing-ref}}
                  :unused-2 {:blah :blah}}]
      (is (thrown? Exception
                   (println (into {} (sc/assemble-system config [:a]))))
          "An exception should be thrown because b-obj refers to :missing-ref")))

  (testing "missing keys from excluded parts of the total system do not cause assembling the sub-system to fail"
    (let [config {:a {:sc/refs {:b :b-obj}}
                  :b-obj {:sc/refs {:c :c-obj}}
                  :c-obj "C"
                  :unused-1 {:foo :bar
                             :sc/refs {:other :unused-2
                                       :missing :missing-ref}}
                  :unused-2 {:blah :blah}}]
      (is (= {:a {:b {:c "C"}}
              :b-obj {:c "C"}
              :c-obj "C"}
             (->> (sc/assemble-system config [:a])
                  (component/start)
                  (into {})))))))

(deftest extending-configs
  (testing "extended config is merged into dependent configs"
    (let [config {:app-1 {:sc/merge [:conn-spec]
                          :app-name "one"}
                  :app-2 {:sc/merge [:conn-spec]
                          :app-name "two"}
                  :conn-spec {:host "localhost"
                              :port 1234
                              :sc/refs {:api-keys :api-keys}}
                  :api-keys [1 2 3 4]}]
      (is (= {:app-1 {:api-keys [1 2 3 4]
                      :app-name "one"
                      :host "localhost"
                      :port 1234}
              :app-2 {:api-keys [1 2 3 4]
                      :app-name "two"
                      :host "localhost"
                      :port 1234}}
             (-> (sc/assemble-system config)
                 (component/start)
                 (select-keys [:app-1 :app-2]))))))

  (testing "extended configs can be overriden"
    (let [config {:a {:sc/merge [:b]
                      :color :green}
                  :b {:color :blue
                      :count 10}}]
      (is (= {:a {:count 10
                  :color :green}
              :b {:color :blue
                  :count 10}}
             (->> (sc/assemble-system config)
                  (component/start)
                  (into {}))))))

  (testing "circular dependencies are not allowed in config merge-defs"
    (let [config {:a {:sc/merge [:b]}
                  :b {:sc/merge [:a]}}]
      (is (thrown? Exception
                   (-> (sc/assemble-system config)
                       (component/start)
                       (select-keys [:app-1 :app-2]))))))

  (testing "circular reference dependencies caused merge-defs are not allowed"
    (let [config {:a {:sc/merge [:b]}
                  :b {:sc/refs [:a]}}]
      (is (thrown? Exception
                   (->> (sc/assemble-system config)
                        (component/start))))))

  (testing "a merge error in a component not part of the final ref tree does not stop system assembly"
    (let [config {:some-data {:number 2}
                  :bad-merge-component {:sc/merge [{:from :not-found}]}
                  :good-merge-component {:sc/merge [:some-data]}
                  :has-refs {:sc/refs [:good-merge-component]}
                  :gets-refs-from-merge {:sc/merge [:has-refs]}}]
      (->> (sc/assemble-system config {:component-ids [:gets-refs-from-merge]})
           (component/start)))))

(deftest detecting-merge-errors

  (testing "merging a non-map src into a map dst throws an exception"
    (let [config {:a {:sc/merge [{:from [:b :one]}]}
                  :b {:one :two}}
          ex (is (thrown? Exception (sc/assemble-system config)))]
      (is (= {:non-map-src {:a [{:to [] :from [:b :one] :select :all}]}}
             (ex-data ex)))))

  (testing "selecting keys on a non-map src throws an exception"
    (let [config {:a {:sc/merge [{:to :target :from [:b :one] :select [:bad-key]}]}
                  :b {:one :two}}
          ex (is (thrown? Exception (sc/assemble-system config)))]
      (is (= {:non-map-select {:a [{:from [:b :one] :select {:bad-key :bad-key} :to [:target]}]}}
             (ex-data ex)))))

  (testing "transitive-from-ref errors are thrown"
    (let [config {:a {:sc/merge [{:to :target :from [:b :one] :select [:bad-key]}]}
                  :b {:one :two
                      :sc/refs [:a]}}
          ex (is (thrown? Exception (sc/assemble-system config [:b])))]
      (is (= {:non-map-select {:a [{:from [:b :one] :select {:bad-key :bad-key} :to [:target]}]}}
             (ex-data ex)))))

  (testing "transitive-from-merge errors are thrown"
    (let [config {:a {:sc/merge [{:to :target :from [:b :one] :select [:bad-key]}]}
                  :b {:one :two}
                  :c {:one :two
                      :sc/merge [{:from :a}]}}
          ex (is (thrown? Exception (sc/assemble-system config [:c])))]
      (is (= {:non-map-select {:a [{:from [:b :one] :select {:bad-key :bad-key} :to [:target]}]}}
             (ex-data ex)))))

  (testing "non-transitive errors are not thrown"
    (let [config {:a {:sc/merge [{:to :target :from [:b :one] :select [:bad-key]}]}
                  :b {:one :two}
                  :c {:one :two}}]
      (is (= {:c {:one :two}}
             (into {} (sc/assemble-system config [:c])))))))

(deftest extends-configs-using-path
  (testing "extending nested configs via a path"
    (let [config {:app-1 {:sc/merge [{:from [:common :conn]}]
                          :app-name "one"}
                  :common {:conn {:host "localhost"
                                  :port 1234}}}]
      (is (= {:app-1 {:app-name "one"
                      :host "localhost"
                      :port 1234}}
             (-> (sc/assemble-system config)
                 (component/start)
                 (select-keys [:app-1]))))))

  (testing "extending configs in sub-map via a path"
    (let [config {:app-1 {:sc/merge [{:to :connection :from [:common :conn]}]
                          :app-name "one"}
                  :common {:conn {:host "localhost"
                                  :port 1234}}}]
      (is (= {:app-1 {:app-name "one"
                      :connection {:host "localhost"
                                   :port 1234}}}
             (-> (sc/assemble-system config)
                 (component/start)
                 (select-keys [:app-1])))))))

(deftest extends-configs-with-key-selection
  (testing "specifying keys in extends only merges in selected keys"
    (let [config {:app-1 {:sc/merge [{:from :common :select [:host]}]
                          :app-name "one"}
                  :common {:host "localhost"
                           :port 1234}}]
      (is (= {:app-1 {:app-name "one"
                      :host "localhost"}}
             (-> (sc/assemble-system config)
                 (component/start)
                 (select-keys [:app-1 :app-2]))))))

  (testing "extending configs in sub-map via a path and key selection"
    (let [config {:app-1 {:sc/merge [{:to :connection :from [:common :conn] :select [:host]}]
                          :app-name "one"}
                  :common {:conn {:host "localhost"
                                  :port 1234}}}]
      (is (= {:app-1 {:app-name "one"
                      :connection {:host "localhost"}}}
             (-> (sc/assemble-system config)
                 (component/start)
                 (select-keys [:app-1]))))))

  (testing "specifying keys with a map allows key renaming"
    (let [config {:app-1 {:sc/merge [{:from :common :select {:hostname :host}}]
                          :app-name "one"}

                  :common {:host "localhost"
                           :port 1234}}]
      (is (= {:app-1 {:app-name "one"
                      :hostname "localhost"}}
             (-> (sc/assemble-system config)
                 (component/start)
                 (select-keys [:app-1 :app-2])))))))

(deftest malformed-create-fn-is-detected
  (let [create-fn 'not.namespace.qualified.map->Foo
        config {:broken-component {:sc/create-fn create-fn}}]
    (when-let [e (is (thrown? ExceptionInfo
                              (sc/assemble-system config)))]
      (is (= ":sc/create-fn for component :broken-component is not a namespace-qualified symbol."
             (.getMessage e)))
      (is (= {:component-key :broken-component
              :component-map (:broken-component config)
              :create-fn create-fn}
             (ex-data e))))))

(deftest invalid-required-in-map-fn
  (let [create-fn 'invalid-namespace/map->Foo
        config {:broken-component {:sc/create-fn create-fn}}]
    (when-let [e (is (thrown? FileNotFoundException
                              (sc/assemble-system config)))]
      (is (= "Could not locate invalid_namespace__init.class or invalid_namespace.clj on classpath. Please check that namespaces with dashes use underscores in the Clojure file name."
             (.getMessage e))))))

(defn ^:private new-component-with-dependency-metadata
  "Returns a component which has existing Component dependency metadata"
  [_]
  (component/using {:my :app} {:web-server :bad-webserver}))

(deftest remove-existing-dependency-metadata
  (testing "existing Component dependency metadata is removed"
    (let [config {:app {:sc/create-fn 'com.walmartlabs.schematic-test/new-component-with-dependency-metadata}}]
      (is (= {:my :app}
             (-> (sc/assemble-system config)
                 (component/start)
                 :app))))))

(deftest test-debug-fn
  (let [config '{:top {:spin :left}
                 :comp/top {:sc/create-fn top
                            :sc/merge [{:from :top}]
                            :sc/refs {:body :comp/body}}
                 :comp/body {:sc/create-fn body}
                 :comp/tail {:sc/create-fn tail}}]
    (testing "no component exclusions"
      (is (= '{:comp/body #:sc{:create-fn body}
               :comp/tail #:sc{:create-fn tail}
               :comp/top {:sc/create-fn top
                          :sc/refs {:body :comp/body}
                          :spin :left}
               :top {:spin :left}}
             (sc/merged-config config))))

    (testing "with selected components"
      (is (= '#:comp{:body #:sc{:create-fn body}
                     :top {:sc/create-fn top
                           :sc/refs {:body :comp/body}
                           :spin :left}}
             (sc/merged-config config [:comp/top]))))))
