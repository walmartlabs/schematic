# Unreleased

Use `com.stuartsierra.dependency/transitive-dependencies` instead of direct Record field access.

# 1.2.0 -- 4 Dec 2018

Added new `com.walmartlabs.schematic.transform` namespace
which adds support for transforming a config before
assembling it.

Initially, this is to allow more concise (and less redundant)
component create functions.

It is now possible for a component to be an arbitrary Java object, rather
than a Clojure map.
Previously, this would result in a runtime exception.
Optionally, you may extend the `com.stuartsierra.component/Lifecycle` protocol
onto the Java object.

[Closed Issues](https://github.com/walmartlabs/schematic/milestone/2?closed=1)

# 1.1.0 -- 29 Mar 2018

Many functions exposed only for testing have been excluded from
documentation. These functions have the ^:no-doc metadata.

The `merged-subconfig` function has been merged into the `merged-config`
function.

[Closed Issues](https://github.com/walmartlabs/schematic/milestone/1?closed=1)
