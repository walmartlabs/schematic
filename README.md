# walmartlabs/schematic

[![Clojars Project](https://img.shields.io/clojars/v/com.walmartlabs/schematic.svg)](https://clojars.org/com.walmartlabs/schematic)

[![CircleCI](https://circleci.com/gh/walmartlabs/schematic.svg?style=svg)](https://circleci.com/gh/walmartlabs/schematic)

Schematic is a Clojure library which aids in assembling [Component][] systems
from configuration data. That configuration data is often read from an `edn` file.
The structure of the configuration data was inspired by [Integrant][].
The goal of Schematic is to reduce the complexity of programmatic
construction of Components, while continuing to use existing Component workflows 
and tools. 

The primary features provided by Schematic are:
* specifying an initialization function for a Component
* declaring Component dependencies (for use in `component/using`)
* declaring merge definitions to enable configuration sharing among Components
* assembling a System using only a subset of the available Components

[component]: https://github.com/stuartsierra/component
[integrant]: https://github.com/weavejester/integrant

[API Documentation](http://walmartlabs.github.io/apidocs/schematic/)

[Blog Post](https://medium.com/@hlship/schematic-92b0b6ffdb26)

## Overview

Schematic starts with a configuration map.
From this map, Schematic will assemble a Component system map, ready to
be started.

Each key/value pair in the configuration map will become
a system key and component in the Component system map.

Schematic uses some special keys to identify

- The constructor for the component (`:sc/create-fn`)
- The Component dependencies for the components (`:sc/refs`)
- Additional rules to manipulate the component's configuration (`:sc/merge`)

The component's configuration is passed to its constructor as part of
building the Component system map.

It is ok to omit the `:sc/create-fn` constructor when a plain map is sufficient.
This may occur when the component doesn't need to implement any protocols,
or when the component exists as a source of configuration for other components.

## Usage

The simplest example is:

```clojure
(require '[com.walmartlabs.schematic :as sc]
         '[com.stuartsierra.component :as component])
 
(def config {:app {:host "localhost"}})  
       
(->> (sc/assemble-system config)

     (component/start-system)
     (into {}) ;; put it into a map just so it prints in the REPL
     )
     
;; => {:app {:host "localhost"}}
```
In this case, Schematic didn't really do any work, 
since there were no declared refs, merge-defs, or create-fns.

A more complete example is:
```clojure
(require '[com.walmartlabs.schematic :as sc]
         '[com.stuartsierra.component :as component])

(defrecord App [server api-keys]
  component/Lifecycle
  (start [this]
   (assoc this :started true))
  
  (stop [this]
   (assoc this :started nil)))

(def config
  {:app {:sc/create-fn 'user/map->App
         :sc/refs {:server :webserver}
         :sc/merge [{:to [:api-keys] :from [:api :keys]}]}
  
   :webserver {:sc/merge [{:from [:host-config]}]
               :port 8080}
   
   :host-config {:host "localhost"}
  
   :api {:username "user"
         :keys [1 2 3 4]}})
   
(->> (sc/assemble-system config)
     (component/start-system)
     (into {}) ;; put it into a map just so it prints in the REPL
     )

;; =>
;; {:api {:username "user", :keys [1 2 3 4]},
;;  :host-config {:host 8080},
;;  :webserver {:host "localhost", :port 8080},
;;  :app #user.App{:server {:host "localhost", :port 8080}, 
;;                 :api-keys [1 2 3 4], 
;;                 :started true}}
```

Notice, in the above, that the Schematic keys (`:sc/create-fn`, etc.) have
been removed, and the configuration for the `:app` component has been
extended via `:sc/merge`.

The following sections will cover aspects seen in the above example.

### Declaring configuration components

Example (edn):
```clojure
{:app {:sc/create-fn user/map->App
       :sc/refs {:server :webserver
                 :api-keys :api-keys}}

 :webserver {:sc/refs [:host]
             :port 8080}
             
 :host 8080

 :api-keys [1 2 3 4]}
```

In this example, `:app`, `:webserver`, `host` and `:api-keys` are all top-level items, 
and will be used to create components and/or be injected into components.
Top-level items can be any type of data, but only `associative` data structures will
receive dependency injection metadata for their references.

### Declaring dependencies

Component dependencies are declared via a key of `:sc/refs` with the value being
a map or vector having the same semantics as `component/using`.

Example:
```clojure
:app {:sc/create-fn user/map->App
      :sc/refs {:server :webserver
                :api-keys :api-keys}}
                
:webserver {:sc/refs [:host]}
```
In `:app`, the refs are a map of component-ids, where the key is the local id of the component,
and the value is the global id of the component.
In `:webserver`, a vector is provided, indicating that the local id and global id of the 
component are the same.

### Creating Components

To specify that a particular configuration item can participate in `component/Lifecycle` functions (start/stop),
the special key -- `:sc/create-fn` -- is used to declare the namespace-qualified name of the function which
which will be called to create the Component instance. This will usually be the default `map->Record` function which `defrecord`
creates for us. But it can also be any regular function which returns an object which implements
the `Lifecycle` interface, and accepts a single map as an argument.

Example (using a `map->Record` constructor function):
```clojure
(ns com.business.system
  (:require [com.walmartlabs.schematic :as sc]
            [com.stuartsierra.component :as component]))

(defrecord App [server api-keys thread-pool-size]
  component/Lifecycle
  (start [this] this)
  (stop [this] this))
  
(def config {:app {:sc/create-fn 'com.business.system/map->App
                   :sc/refs {:server :webserver
                             :api-keys :api-keys}
                   :thread-pool-size 20}
             ;; remaining config omitted     
             })
```
Note `:sc/create-fn 'com.business.system/map->App` which will cause the `App` record to be created
when the system is assembled.

Example (using plain function):
```clojure
(ns com.business.system
  (:require [com.walmartlabs.schematic :as sc]
            [com.stuartsierra.component :as component]))

(defrecord App [server api-keys thread-pool-size]
  component/Lifecycle
  (start [this] this)
  (stop [this] this))
  
(defn make-app [{:keys [thread-pool-size] :or {thread-pool-size 10}}]
  (map->App {:thread-pool-size thread-pool-size}))
  
(def config {:app {:sc/create-fn 'com.business.system/make-app
                   :sc/refs {:server :webserver
                             :api-keys :api-keys}
                   :thread-pool-size 20}
             ;; remaining config omitted     
             })
```
In the 'plain function' example, you can see that it is possible to set a default value
for `thread-pool-size` in the `make-app` function if a value were not to be provided in the config.
That is just one possible use case for using a plain function constructor.
 
### Dependency injection

For each top-level, `map?` item in the config, Schematic will extract a list of the declared 
`:sc/refs` and attach the needed Component metadata by calling `(component/using component ref-map)`.
The resulting value will be a Component which can be started and stopped.

### Merging common configuration

In complex applications, there may be configuration that is shared or duplicated across components,
the `:sc/merge` key is a set of rules for selecting, renaming, and injecting global configuration
into a component's specific configuration.

The merge happens before `:sc/refs` are processed.

```clojure
:host-config {:host "localhost"
              :port 8080}

:app {:sc/merge [{:from [:host-config]}]}
```
In this example, `:host-config` map will be merged into the `:app` map. 

* `:sc/merge` accepts a vector of merge-defs.
* a merge-def can provide `:to`, `:from`, and `:select` keys
* `:to` indicates the key/key-path in the component config where the results will be merged. It defaults to `[]`. 
* `:from` indicates the key/key-path from the global config where values will be copied from
* `:select` is either a vector of key-names or a map of local-key-names to from-key-names (names of fields in `:from`). 
It defaults to the special keyword `:all`, which indicates to copy the entire `:from` data object.

### Assembling the system
 
The actual work of processing merge-defs, analyzing the `:sc/refs`, and adding the dependency metadata happens in the
`com.walmartlabs.schematic/assemble-system` function. That function takes a config map
and returns a System map which can be started and stopped.

Example:
```clojure
(->> {:webserver {:sc/refs [:host :port]}
      :host "localhost"
      :port 8080}
     (sc/assemble-system)
     (component/start-system))

;; => #<SystemMap>
```

`assemble-system` optionally takes a configuration map
 and an optional list of component-ids to include in the final system.

#### Assembling a sub-system using component-ids

In some cases it might be desirable to include only a subset of the available config 
when assembling a system, so that only the needed components are started and stopped.
Two possible such scenarios are: 
* The config is shared among multiple applications, which 
each need a portion of the components at runtime, but not all of them.
* At the REPL, it might be useful to get a particular component and start it, such as a database connection.

In such cases, the `:component-ids` argument can be provided to `assemble-system`.
The component-ids represent the top-level components which must be included in the final system.
Only these top-level components and all of their transitive dependencies will be included in
the final system map.

Example (include single app):
```clojure
(-> {:app-1 {:sc/refs {:conn :db-conn
                       :product-api :product-api}}
     :app-2 {:sc/refs {:conn :db-conn
                       :customer-api :customer-api}}
     :db-conn {:sc/refs {:host :db-host}
               :username "user"
               :password "secret"}
     :product-api {}
     :customer-api {}
     :db-host "localhost"}
    (sc/assemble-system [:app-1])
    (component/start-system)
    ((partial into {})))
;; =>
;; {:product-api {},
;;  :app-1 {:conn {:host "localhost", :username "user", :password "secret"}, :product-api {}},
;;  :db-host "localhost",
;;  :db-conn {:host "localhost", :username "user", :password "secret"}}
```
Notice that `:app-2` and `:customer-api` have not been included in the final system.

Example (development at the REPL):
```clojure
(let [config {:app-1 {:sc/refs {:conn :db-conn
                                :product-api :product-api}}
              :app-2 {:sc/refs {:conn :db-conn
                                :customer-api :customer-api}}
              :db-conn {:sc/refs {:host :db-host}
                        :username "user"
                        :password "secret"}
              :product-api {}
              :customer-api {}
              :db-host "localhost"}
      system (-> (sc/assemble-system config [:db-conn :product-api])
                 (component/start-system))
      {:keys [db-conn product-api]} system]
  (println (into {} system))
  ;; do something here with db-conn or product-api
  (component/stop system)
  nil)

;; {:product-api {}, 
;;  :db-host localhost, 
;;  :db-conn {:host localhost, :username user, :password secret}}    
;; => nil 
```
In this example, we were able to use just the database component and product-api to do some
testing in the REPL.

## License

Copyright (c) 2017-present, Walmart Inc.

Distributed under the Apache Software License 2.0.
