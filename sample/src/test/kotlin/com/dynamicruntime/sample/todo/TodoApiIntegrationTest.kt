package com.dynamicruntime.sample.todo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication

/**
 * Integration test that boots the whole Ktor application in-process via
 * `testApplication` and drives the real HTTP CRUD contract of [todoModule].
 * Each block gets a fresh application (and therefore a fresh in-memory
 * repository seeded with the two default todos), so the cases are independent.
 */
class TodoApiIntegrationTest : StringSpec({

    // A test client that can (de)serialize the JSON DTOs, mirroring a real caller.
    fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json() }
    }

    "GET /api/todos returns the seed todos" {
        testApplication {
            application { todoModule() }
            val todos: List<Todo> = jsonClient().get("/api/todos").body()

            todos.map { it.title } shouldBe listOf("Try the Ktor todo API", "Wire it into the React UI")
        }
    }

    "POST /api/todos creates a todo and returns 201 with the created body" {
        testApplication {
            application { todoModule() }
            val client = jsonClient()

            val response = client.post("/api/todos") {
                contentType(ContentType.Application.Json)
                setBody(CreateTodoRequest("Buy milk"))
            }
            response.status shouldBe HttpStatusCode.Created

            val created: Todo = response.body()
            created.title shouldBe "Buy milk"
            created.completed shouldBe false

            // The new todo is now part of the list.
            val titles = client.get("/api/todos").body<List<Todo>>().map { it.title }
            titles.contains("Buy milk") shouldBe true
        }
    }

    "POST with a blank title is rejected with 400" {
        testApplication {
            application { todoModule() }
            val response = jsonClient().post("/api/todos") {
                contentType(ContentType.Application.Json)
                setBody(CreateTodoRequest("   "))
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "PUT /api/todos/{id} edits title and completion" {
        testApplication {
            application { todoModule() }
            val client = jsonClient()

            val updated: Todo = client.put("/api/todos/1") {
                contentType(ContentType.Application.Json)
                setBody(UpdateTodoRequest(title = "Renamed", completed = true))
            }.body()

            updated.id shouldBe 1
            updated.title shouldBe "Renamed"
            updated.completed shouldBe true
        }
    }

    "PUT of a missing id returns 404" {
        testApplication {
            application { todoModule() }
            val response = jsonClient().put("/api/todos/9999") {
                contentType(ContentType.Application.Json)
                setBody(UpdateTodoRequest(completed = true))
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    "DELETE removes a todo (204) and a second delete is 404" {
        testApplication {
            application { todoModule() }
            val client = jsonClient()

            val first: HttpResponse = client.delete("/api/todos/1")
            first.status shouldBe HttpStatusCode.NoContent

            val second: HttpResponse = client.delete("/api/todos/1")
            second.status shouldBe HttpStatusCode.NotFound

            // Id 1 is gone; only the second seed remains.
            val ids = client.get("/api/todos").body<List<Todo>>().map { it.id }
            ids shouldBe listOf(2)
        }
    }

    "a non-integer id returns 400" {
        testApplication {
            application { todoModule() }
            jsonClient().get("/api/todos/abc").status shouldBe HttpStatusCode.BadRequest
        }
    }
})
