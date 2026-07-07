package com.dynamicruntime.webapp

/**
 * Client-side view of a todo, matching the JSON the `:sample` runtime's `todo` endpoints return (see the
 * sample module's Todo.kt). Responses are read straight off the parsed JSON in [TodoApi] rather than through
 * a serialization library, so no `@Serializable` is needed. Request bodies are built inline in [TodoApi],
 * so the old Create/Update request DTOs are gone.
 */
data class Todo(
    val id: Int,
    val title: String,
    val completed: Boolean,
)
