(ns com.walmartlabs.schematic.transform
  "Utilities for transforming a Schematic configuration before it is assembled."
  {:added "1.2.0"}
  (:require
    [com.walmartlabs.schematic.lang :as lang]))

(defn extend-symbol
  "Transforms a qualified symbol, appending the namespace prefix and a dot,
  and possibly adding a `map->` prefix to the name, if the symbol name
  starts with an upper case character (which is assumed to be the name of a record)."
  [namespace-prefix sym]
  (let [symbol-name (name sym)
        symbol-name' (if (-> symbol-name (.charAt 0) (Character/isUpperCase))
                       (str "map->" symbol-name)
                       symbol-name)]
    ;; Rebuild the namespace of the symbol, adding a prefix to the namespace
    ;; and, optionally, the symbol name.
    (symbol (->> sym namespace (str namespace-prefix "."))
            symbol-name')))

(defn xform-constructor-fn
  "Returns a transform function for supporting concise constructor functions.
   The `k` key in a component, when present, is replaced with a `:sc/create-fn`
   key, where the value is extended via [[extend-symbol]].

   In practice, this means that the config can contain
   `:foo/create-fn` -> `bar/Baz`, which will be converted to
   `:sc/create-fn` -> `org.example.bar/map->Baz` (assuming a
   namespace prefix of `org.example`).

   The namespace prefix may be a string or a symbol."
  [k namespace-prefix]
  (fn [component]
    (let [sym (get component k)]
      (if (qualified-symbol? sym)
        (-> component
            (dissoc k)
            (assoc :sc/create-fn (extend-symbol namespace-prefix sym)))
        component))))

(defn apply-xforms
  "Applies a seq of transform functions to the components of the configuration.

  Each xform function is passed a configuration map and returns the same, or a modified
  version."
  [config xforms]
  (lang/map-vals
    #(reduce (fn [c xform]
               (xform c))
             %
             xforms)
    config))
