package com.dynamicruntime.sample.todo

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Registers the Todo CRUD endpoints under `/api/todos`, backed by [repository].
 *
 *   GET    /api/todos        -> list all todos
 *   POST   /api/todos        -> create  { "title": "..." }        -> 201
 *   GET    /api/todos/{id}   -> fetch one                          -> 404 if absent
 *   PUT    /api/todos/{id}   -> edit    { "title"?, "completed"? } -> 404 if absent
 *   DELETE /api/todos/{id}   -> remove                             -> 204 / 404
 */
fun Route.todoRoutes(repository: TodoRepository) {
    route("/api/todos") {
        get {
            call.respond(repository.all())
        }

        post {
            val request = call.receive<CreateTodoRequest>()
            if (request.title.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "title must not be blank"))
                return@post
            }
            call.respond(HttpStatusCode.Created, repository.add(request))
        }

        get("/{id}") {
            val id = call.todoId() ?: return@get
            val todo = repository.get(id)
            if (todo == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "no todo with id $id"))
            } else {
                call.respond(todo)
            }
        }

        put("/{id}") {
            val id = call.todoId() ?: return@put
            val updated = repository.update(id, call.receive<UpdateTodoRequest>())
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "no todo with id $id"))
            } else {
                call.respond(updated)
            }
        }

        delete("/{id}") {
            val id = call.todoId() ?: return@delete
            if (repository.delete(id)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "no todo with id $id"))
            }
        }
    }
}

/**
 * Parses the `{id}` path segment as an Int. On a malformed id, responds 400 and
 * returns null so the caller can `return@...` early.
 */
private suspend fun io.ktor.server.application.ApplicationCall.todoId(): Int? {
    val id = parameters["id"]?.toIntOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "id must be an integer"))
    }
    return id
}
