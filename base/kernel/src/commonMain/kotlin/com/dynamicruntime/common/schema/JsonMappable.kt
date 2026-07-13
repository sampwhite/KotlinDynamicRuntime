package com.dynamicruntime.common.schema

/**
 * Something that can render itself as a JSON-style `Map<String, Any?>` -- the form used when it is
 * serialized into an endpoint response. Implemented by data classes that keep their own serialization
 * (and, alongside it, their schema definition) rather than deferring to ad hoc conversion code elsewhere.
 * Centralizing both on the class keeps schema and serialization aligned in one place, and lets generic
 * response handling be written against this interface.
 */
interface JsonMappable {
    /** Renders this object as an insertion-ordered JSON-style map. */
    fun toJsonMap(): Map<String, Any?>
}
