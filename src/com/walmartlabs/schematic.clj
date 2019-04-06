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

(ns com.walmartlabs.schematic
  (:require [clojure.pprint]
            [com.stuartsierra.dependency :as dep]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.schematic.lang :as lang]
            [clojure.set :as set]
            [clojure.string :as str])
  (:refer-clojure :exclude [ref])
  (:import (clojure.lang IObj)))

;; ---------------------------------------------------------

(defn ^:no-doc dependency-graph
  "Creates a dependency graph with dependencies identified by find-dep-fn"
  [config find-dep-fn]
  (reduce-kv (fn [g k v] (reduce #(dep/depend %1 k %2) g (find-dep-fn v)))
             (dep/graph)
             config))

;; ---------------------------------------------------------
;; ## Finding/configuring component references/dependencies

(defn ^:no-doc referred-component-ids
  "Returns a sequence of component-id's for refs declared in component-config"
  [component-config]
  (some-> (:sc/refs component-config)
          (lang/mapify-seq)
          (vals)))

(defn ^:private missing-refs
  "Identifies any missing component references; Returns a map where the key is
  a component id and the value is a set of missing references for that component id."
  [config]
  (let [all-declared-component-ids (-> config keys set)
        reducer (fn [m component-id component]
                  (let [refs (-> component referred-component-ids set)
                        bad-refs (set/difference refs all-declared-component-ids)]
                    (if (seq bad-refs)
                      (assoc m component-id bad-refs)
                      m)))]
    (reduce-kv reducer {} config)))

(defn ^:no-doc ref-map-for-component
  "Finds refs declared in the component v and returns a map of local-names to system refs"
  [v]
  (or (lang/mapify-seq (:sc/refs v)) {}))

(defn ^:no-doc resolve-init-fn [m]
  (when-let [f-id (get m :sc/create-fn)]
    (if-let [f (resolve f-id)]
      f
      (throw (ex-info (str "Unable to resolve declared create-fn: " f-id ". Perhaps the enclosing namespace needs to be 'required'.")
                      {:fn f-id
                       :config m})))))

(defn ^:no-doc associate-dependency-metadata
  "If v is an associative structure, finds any declared dependencies and associates appropriate
   Component metadata. If it is not associative, the original value is returned.
   Any pre-existing ::component/dependencies will be removed. "
  [v]
  ;; Most every value in a schematic system is a map defining the component.
  ;; However, at the top level, there can be key/value pairs where the value is a scalar type;
  ;; these can be read and injected (via :sc/merge) into components, so leave those alone.
  (if-not (map? v)
    v
    (let [ref-map (ref-map-for-component v)
          init-fn (resolve-init-fn v)
          component' (cond-> (dissoc v :sc/create-fn :sc/refs)
                       init-fn (init-fn))]
      (if (instance? IObj component')
        (-> component'
            ;; Sanity: clear any existing dependencies already present, though
            ;; Such dependencies might exist in the metadata if a plain-Component constructor
            ;; function is being re-used as a Schematic component init function.
            ;; Schematic does not honor any dependency metadata applied by other means.
            (vary-meta dissoc ::component/dependencies)
            (component/using ref-map))
        component'))))

(defn ^:private joined-list
  [coll]
  (->> coll
       sort
       (map str)
       (str/join ", ")))

(defn ^:no-doc throw-on-missing-refs [config]
  (let [missing (missing-refs config)]
    (when-not (empty? missing)
      (throw (ex-info (str "Missing definitions for refs: "
                           (->> missing
                                vals
                                (reduce into)
                                joined-list)
                           " in components: "
                           (-> missing keys joined-list))
                      {:reason ::missing-refs
                       :references missing})))))

(defn ^:no-doc ref-dependency-graph
  "Return a dependency graph of all the refs in a config."
  [config]
  (dependency-graph config referred-component-ids))

(defn ^:private validate-create-fns
  [config]
  (doseq [[component-key component-map] config]
    (when (and (map? component-map)
               (some? (:sc/create-fn component-map)))
      (let [create-fn (:sc/create-fn component-map)]
        (if-not (and (symbol? create-fn)
                     (namespace create-fn))
          (throw (ex-info (format ":sc/create-fn for component %s is not a namespace-qualified symbol." component-key)
                          {:component-key component-key
                           :component-map component-map
                           :create-fn create-fn})))))))

(defn ^:no-doc find-create-fn-namespaces
  "Returns a collection of symbols representing the namespaces of :sc/create-fn functions."
  [config]
  (->> (vals config)
       (filter map?)
       (keep #(:sc/create-fn %))
       (distinct)
       (map namespace)
       (map symbol)))

(defn ^:no-doc load-namespaces!
  "Automatically loads namespaces used in :sc/create-fn's.
   For convenience in threading macros, returns the config."
  [config]
  (doseq [the-ns (find-create-fn-namespaces config)]
    (require the-ns))
  config)

;; --------------------------------------
;; ## Finding/applying merge definitions

(defn ^:no-doc normalize-merge-def
  "Converts a merge definition to a standard form, which is:
   {:to [:bar] :from [:foo] :select {:my-host :host}}
   or
   {:to [] :from [:foo] :select :all}
   "
  [merge-def]
  (cond
    (keyword? merge-def) {:to [] :from [merge-def] :select :all}
    (map? merge-def) (let [{:keys [to from select]
                            :or {to []
                                 select :all}} merge-def
                           select (if (sequential? select)
                                    (zipmap select select)
                                    select)]
                       {:to (if (keyword? to)
                              [to]
                              to)
                        :from (if (keyword? from)
                                [from]
                                from)
                        :select select})
    :else nil))

(defn ^:no-doc merge-def-root
  "Given a merge definition, return the root element of the data to be copied/merged.
   The root element is the first key in the path to the the source data.

   The following definitions all have a root of :foo
   >  :foo
   >  {:from :foo}
   >  {:from [:foo :bar]}"
  [merge-def]
  (-> (normalize-merge-def merge-def)
      :from
      first))

(defn ^:no-doc merge-errors-of-type [error-type errors]
  (->> (map error-type errors)
       (apply merge-with (comp distinct concat))))

(defn ^:no-doc throw-on-merge-errors
  "Examines the metadata of the provided config.
   If any ::merge-errors are found, they are collapsed into a
   single set of data which is thrown in an exception.

   Note: the config passed to this function may represent only a subset
   of the overall possible config. It is possible that other parts
   of the overall config had merge-errors. But since those parts
   were removed from this config, they will not adversely affect
   the ability to use *this* config to create and start a System."
  [config]
  (let [errors (->> (vals config)
                    (map meta)
                    (map ::merge-errors)
                    (filter identity))]
    (when (seq errors)
      (let [error-types (->> (map keys errors)
                             (flatten)
                             (distinct))
            ex-data (reduce #(assoc %1 %2 (merge-errors-of-type %2 errors)) {} error-types)]
        (throw (ex-info "Errors occurred during merge process" ex-data))))))

(defn ^:private add-merge-error-meta
  [obj error-type component-id merge-def]
  (vary-meta obj update-in [::merge-errors error-type component-id] conj merge-def))

(defn ^:no-doc merge-value
  "Evaluates the merge definition and applies the change to the component config.
   Note, the semantics for selecting/renaming keys is the inverse of clojure.set/rename-keys.
   This is due to the fact that Component declares dependencies using {:local :system} semantics,
   so the same principle was used for merging maps in order to have a consistent \"api\"."
  [component-config component-id merge-def config]
  (let [{:keys [to from select] :as normalized} (normalize-merge-def merge-def)
        src-component (get config (first from))
        component-config (with-meta component-config (lang/deep-merge
                                                      (meta component-config)
                                                      (meta src-component)))
        src-value (get-in config from)
        dst-is-component-root? (empty? to)
        update-f (fn [new-value]
                   (if (seq to)
                     (update-in component-config to lang/deep-merge new-value)
                     (lang/deep-merge component-config new-value)))]
    (cond
      ;; Only map types can be applied (merged) into the root of a component
      (and dst-is-component-root?
           (not (map? src-value))) (add-merge-error-meta component-config :non-map-src component-id normalized)

      ;; By specifying 'select', it indicates that the src-value should be a map to
      ;; be selected from. If src-value is not a map, that is an error.
      (and (not= select :all)
           (not (map? src-value))) (add-merge-error-meta component-config :non-map-select component-id normalized)

      ;; Merge all of src-value into dst
      (= :all select) (update-f src-value)

      ;; Select a subset of src-value, and merge that into dst
      :else (let [source-keys (vals select)]
              (-> (select-keys src-value source-keys)
                  (set/rename-keys (set/map-invert select))
                  (update-f))))))

(defn ^:no-doc find-merge-defs
  "Finds extracts merge-defs in v, if any.
   A merge-def is declared via `:sc/merge`."
  [v]
  (when (map? v)
    (:sc/merge v)))

(defn ^:no-doc apply-merge-defs
  "Applies an optional :sc/merge declaration in m"
  [m config node-id]
  (if-let [merge-defs (:sc/merge m)]
    (-> (reduce #(merge-value %1 node-id %2 config) {} merge-defs)
        (lang/deep-merge (dissoc m :sc/merge-keys-in :sc/merge)))
    m))

(defn ^:no-doc merges-dependency-graph
  "Return a dependency graph of all the merge-defs in a config."
  [config]
  (dependency-graph config #(map merge-def-root (find-merge-defs %))))

;; -------------------------------------------------

(defn ^:no-doc validate-config!
  "Validates various aspects of the config, throwing an exception for any issues found.
   For convenience in threading macros, returns the config."
  [config]
  (validate-create-fns config)
  (throw-on-merge-errors config)
  (throw-on-missing-refs config)
  config)

(defn ^:no-doc subconfig-for-components
  "Returns a map of just the given components and their transitive dependencies"
  [system-config component-ids]
  (let [dep-graph (ref-dependency-graph system-config)
        target-keys (->> (mapcat (partial dep/transitive-dependencies dep-graph) component-ids)
                         (into component-ids)
                         (set))]
    (select-keys system-config target-keys)))

;; -------------------------------------------------
;; ## Public API

(defn merged-config
  "Applies any merge definitions, then  optionally selects just the specified subset of components, including
  transitive dependencies.

  This is useful for testing, to see the intermediate state before components are instantiated."
  ([config]
   (merged-config config nil))
  ([config component-ids]
   (let [dep-graph (merges-dependency-graph config)
         topo-nodes (dep/topo-sort dep-graph)
         merged-config (reduce (fn [config node-id]
                                 (update config node-id #(apply-merge-defs % config node-id)))
                               config topo-nodes)]
     (cond-> merged-config
       (seq component-ids) (subconfig-for-components component-ids)))))

(defn assemble-system
  "Assembles config into a system-map which can be used with `com.stuartsierra.component/start`.

  config - configuration for the system

  component-ids - a sequence of component ids. When provided, only the neccessary
  parts of the system map to support those components will be inlcluded
  in the final system map.
  By default, the returned system contains all components from the supplied config."
  ([config]
   (assemble-system config nil))
  ([config component-ids]
   (-> (merged-config config component-ids)
       (validate-config!)
       (load-namespaces!)
       (->> (lang/map-vals #(associate-dependency-metadata %)))
       (component/map->SystemMap))))
