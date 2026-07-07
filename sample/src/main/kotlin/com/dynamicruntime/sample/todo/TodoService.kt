package com.dynamicruntime.sample.todo

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The sample app's service: it owns an in-memory [TodoRepository] and defines the Todo CRUD endpoints on top
 * of the runtime's own endpoint framework -- no external web framework (Ktor is gone). Following the
 * [com.dynamicruntime.common.node.NodeService] convention, the service defines its endpoints inline in
 * [schema] (the file is small) and the `SampleComponent` wires them in. Handlers resolve the live service
 * per request via [get], so they always act on the instance's repository.
 *
 * The endpoints sit in the `todo` namespace; their paths live under the `/todo/...` section. Because the
 * endpoint framework matches on an exact `path:method` (there is no path-parameter extraction yet), the id
 * for update/delete travels in the request body rather than in the URL.
 */
class TodoService : ServiceInitializer {
    override val serviceName: String = TodoService.serviceName

    /** The instance's todo store. Created with two seed items (see [TodoRepository]). */
    val repository = TodoRepository()

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "TodoService"

        fun get(cxt: KdrCxt): TodoService =
            cxt.instanceConfig.get(serviceName) as? TodoService
                ?: throw KdrException("TodoService is not available.")

        /**
         * The Todo types and endpoints, contributed by the `SampleComponent`. A `list` endpoint returns the
         * items under `items`; `add`/`update` are general endpoints returning the affected todo under
         * `results`; `delete` returns a small `{deleted}` result. Missing ids fault with a 404, blank titles
         * with a 400 -- carried by [KdrException]'s HTTP `code`.
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "todo") {
            type("Todo") {
                type = SCT.kObject
                property(TD.id, "Unique id of the todo.", required = true) { type = SCT.integer }
                property(TD.title, "The todo's title.", required = true)
                property(TD.completed, "Whether the todo has been completed.", required = true) {
                    type = SCT.boolean
                }
            }
            type("CreateTodo") {
                type = SCT.kObject
                property(TD.title, "Title for the new todo.", required = true)
            }
            type("UpdateTodo") {
                type = SCT.kObject
                property(TD.id, "Id of the todo to update.", required = true) { type = SCT.integer }
                property(TD.title, "New title; omit to leave it unchanged.")
                property(TD.completed, "New completed state; omit to leave it unchanged.") { type = SCT.boolean }
            }
            type("DeleteTodo") {
                type = SCT.kObject
                property(TD.id, "Id of the todo to delete.", required = true) { type = SCT.integer }
            }
            type("DeleteResult") {
                type = SCT.kObject
                property(TD.deleted, "Whether a todo with that id was removed.", required = true) {
                    type = SCT.boolean
                }
            }

            listEndpoint("/todo/list", "List all todos, in creation order.", outputRef = "Todo") { c, _ ->
                get(c).repository.all().map { it.toJsonMap() }
            }

            generalEndpoint(
                "/todo/add",
                "Create a new (not-completed) todo.",
                HttpMethod.POST,
                outputRef = "Todo",
                inputRef = "CreateTodo",
            ) { c, request ->
                val title = (request[TD.title] as? String).orEmpty()
                if (title.isBlank()) {
                    throw KdrException.mkInput("title must not be blank.")
                }
                get(c).repository.add(title).toJsonMap()
            }

            generalEndpoint(
                "/todo/update",
                "Update a todo's title and/or completion state.",
                HttpMethod.POST,
                outputRef = "Todo",
                inputRef = "UpdateTodo",
            ) { c, request ->
                val id = todoId(request)
                get(c).repository.update(id, request[TD.title] as? String, request[TD.completed] as? Boolean)
                    ?.toJsonMap()
                    ?: throw KdrException("No todo with id $id.", code = EXC.notFound)
            }

            generalEndpoint(
                "/todo/delete",
                "Delete a todo by id.",
                HttpMethod.POST,
                outputRef = "DeleteResult",
                inputRef = "DeleteTodo",
            ) { c, request ->
                val id = todoId(request)
                if (!get(c).repository.delete(id)) {
                    throw KdrException("No todo with id $id.", code = EXC.notFound)
                }
                linkedMapOf(TD.deleted to true)
            }
        }

        /** Reads the required, validated `id` field (coerced to an integer) from a request map. */
        private fun todoId(request: Map<String, Any?>): Int =
            (request[TD.id] as? Number)?.toInt()
                ?: throw KdrException.mkInput("id must be an integer.")
    }
}
