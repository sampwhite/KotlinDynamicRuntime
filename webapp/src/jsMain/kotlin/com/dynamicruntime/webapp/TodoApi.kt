package com.dynamicruntime.webapp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json

/**
 * Thin Ktor-client wrapper over the `:sample` Todo REST API. Every call is a
 * suspend function, invoked from React effects/handlers via a coroutine scope.
 *
 * The API runs as a separate process on port 8081 (start it with
 * `./gradlew :sample:run`), so the base URL is absolute and cross-origin — the
 * server enables CORS to permit it during development.
 */
object TodoApi {
    private const val baseUrl = "http://localhost:8081/api/todos"

    private val client = HttpClient {
        install(ContentNegotiation) { json() }
    }

    suspend fun list(): List<Todo> = client.get(baseUrl).body()

    suspend fun add(title: String): Todo =
        client.post(baseUrl) {
            contentType(ContentType.Application.Json)
            setBody(CreateTodoRequest(title))
        }.body()

    suspend fun update(id: Int, request: UpdateTodoRequest): Todo =
        client.put("$baseUrl/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun delete(id: Int) {
        client.delete("$baseUrl/$id")
    }
}
