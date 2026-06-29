package com.dynamicruntime.common.context

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
        fun get(cxt: KdrCxt): KdrSchemaStore = KdrSchemaStore()
    }
}
