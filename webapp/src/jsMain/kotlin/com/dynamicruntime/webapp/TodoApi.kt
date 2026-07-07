package com.dynamicruntime.webapp

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The path the `:sample` runtime serves the Todo endpoints under: the `kda` API context root, then the
 * `/todo/...` section. The webapp calls this relative URL; in dev the webpack server proxies `/kda` to the
 * runtime on :7070 (see build.gradle.kts), so the calls are same-origin and need no CORS.
 */
private const val todoBase = "/kda/todo"

/**
 * Binding to the browser's global `fetch`. Declared here (named `browserFetch` to avoid clashing with any
 * wrapper `fetch`) so the webapp needs no HTTP-client library — the Todo endpoints are plain JSON.
 */
@JsName("fetch")
private external fun browserFetch(input: String, init: dynamic = definedExternally): Promise<dynamic>

/**
 * Thin wrapper over the Todo endpoints exposed by the `:sample` runtime app, using the browser Fetch API
 * directly. Every response is the runtime's protocol envelope, so a list arrives under `items` and a single
 * result under `results`; these helpers unwrap it into [Todo]s. Every call is a suspend function, invoked
 * from React effects/handlers via a coroutine scope.
 */
object TodoApi {
    /** GET the todo list (under `items`). */
    suspend fun list(): List<Todo> {
        val items = getJson("$todoBase/list").items
        val n = items.length as Int
        val out = ArrayList<Todo>(n)
        for (i in 0 until n) {
            out.add(toTodo(items[i]))
        }
        return out
    }

    /** POST a new todo; the created item comes back under `results`. */
    suspend fun add(title: String): Todo {
        val body: dynamic = js("({})")
        body.title = title
        return toTodo(postJson("$todoBase/add", body).results)
    }

    /** POST an update by id; omitted fields are left unchanged. The updated item comes back under `results`. */
    suspend fun update(id: Int, title: String? = null, completed: Boolean? = null): Todo {
        val body: dynamic = js("({})")
        body.id = id
        if (title != null) body.title = title
        if (completed != null) body.completed = completed
        return toTodo(postJson("$todoBase/update", body).results)
    }

    /** POST a delete by id. */
    suspend fun delete(id: Int) {
        val body: dynamic = js("({})")
        body.id = id
        postJson("$todoBase/delete", body)
    }

    private suspend fun getJson(url: String): dynamic {
        val response = browserFetch(url).await()
        if (!(response.ok as Boolean)) {
            error("GET $url failed with status ${response.status}")
        }
        // `response` is dynamic, so `response.json()` is dynamic too; cast it to a typed Promise so `.await()`
        // resolves to the Kotlin coroutines extension rather than a (nonexistent) JS `await` method.
        return (response.json() as Promise<dynamic>).await()
    }

    private suspend fun postJson(url: String, body: dynamic): dynamic {
        val init: dynamic = js("({})")
        init.method = "POST"
        val headers: dynamic = js("({})")
        headers["Content-Type"] = "application/json"
        init.headers = headers
        init.body = JSON.stringify(body)
        val response = browserFetch(url, init).await()
        if (!(response.ok as Boolean)) {
            error("POST $url failed with status ${response.status}")
        }
        // `response` is dynamic, so `response.json()` is dynamic too; cast it to a typed Promise so `.await()`
        // resolves to the Kotlin coroutines extension rather than a (nonexistent) JS `await` method.
        return (response.json() as Promise<dynamic>).await()
    }

    private fun toTodo(o: dynamic): Todo =
        Todo(id = o.id as Int, title = o.title as String, completed = o.completed as Boolean)
}
