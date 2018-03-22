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

(ns ^:no-doc com.walmartlabs.schematic.lang
  "Internal utilities used by Schematic.")

(defn map-vals
  "Maps f over every value in the hash-map mm and returns a new
  hash-map. E.g (map-vals inc {:a 1 :b 2}) => {:a 2 :b 3}"
  [f mm]
  (reduce-kv (fn [output k v]
               (assoc output
                      k (f v)))
             {}
             mm))

(defn deep-merge
  "Like merge, but merges maps recursively."
  [& maps]
  (if (every? (some-fn nil? map?) maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn mapify-seq
  "For a seq input, returns a map of each entry mapped to itself.
   If a map is provided, it is returned unchanged.

   (mapify-seq [1 2])
   => {1 1 2 2}"
  [coll]
  (if (sequential? coll)
    (zipmap coll coll)
    coll))
