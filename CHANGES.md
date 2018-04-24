# 1.2.0 -- UNRELEASED

Added new `com.walmartlabs.schematic.transform` namespace
which adds support for transforming a config before
assembling it.

Initially, this is to allow more concise (and less redundant)
component create functions.

# 1.1.0 -- 29 Mar 2018

Many functions exposed only for testing have been excluded from
documentation. These functions have the ^:no-doc metadata.

The `merged-subconfig` function has been merged into the `merged-config`
function.
