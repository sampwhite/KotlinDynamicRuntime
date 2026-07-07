package com.dynamicruntime.webapp

import kotlinx.serialization.Serializable

/**
 * Client-side mirrors of the `:sample` Todo API DTOs. They deliberately match
 * the server's JSON shapes (see the sample module's Todo.kt) so the Ktor client
 * can (de)serialize them. Kept as a small, intentional duplication rather than
 * introducing a shared multiplatform module for this example.
 */
@Serializable
data class Todo(
    val id: Int,
    val title: String,
    val completed: Boolean,
)

@Serializable
data class CreateTodoRequest(
    val title: String,
)

@Serializable
data class UpdateTodoRequest(
    val title: String? = null,
    val completed: Boolean? = null,
)
