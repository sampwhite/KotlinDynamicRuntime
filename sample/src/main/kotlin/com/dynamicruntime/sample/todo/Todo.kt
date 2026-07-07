package com.dynamicruntime.sample.todo

import kotlinx.serialization.Serializable

/**
 * A single todo item. This is both the in-memory record and the JSON shape the
 * REST API returns.
 */
@Serializable
data class Todo(
    val id: Int,
    val title: String,
    val completed: Boolean,
)

/** Request body for `POST /api/todos`. */
@Serializable
data class CreateTodoRequest(
    val title: String,
)

/**
 * Request body for `PUT /api/todos/{id}`. Both fields are optional so a caller
 * can rename a todo, toggle its completion, or both, in one request.
 */
@Serializable
data class UpdateTodoRequest(
    val title: String? = null,
    val completed: Boolean? = null,
)
