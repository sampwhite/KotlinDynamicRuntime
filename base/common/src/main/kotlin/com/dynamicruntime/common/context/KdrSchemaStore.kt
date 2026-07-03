package com.dynamicruntime.common.context

import com.dynamicruntime.common.schema.LogSchema

/**
 * Stubbed placeholder for the read-only schema store. A real implementation will
 * hold the parsed, linked JSON schema for the application and be fundamental to
 * most processing -- which is why a context caches a reference to it. For now it
 * is an empty placeholder.
 */
class KdrSchemaStore {
    companion object {
        /**
         * Builds (or, eventually, retrieves a cached) schema store for the given
         * context. Placeholder: currently returns a fresh empty store.
         */
        fun get(cxt: KdrCxt): KdrSchemaStore {
            LogSchema.debug(cxt, "Creating read only schema store from raw modifiable data inputs.")
            return KdrSchemaStore()
        }
    }
}
