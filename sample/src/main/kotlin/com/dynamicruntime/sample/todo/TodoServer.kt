package com.dynamicruntime.sample.todo

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/** Port the Todo API listens on. Chosen to avoid the webapp dev server (:8080). */
const val TODO_SERVER_PORT = 8081

/**
 * Ktor application module: JSON (de)serialization, permissive CORS for local
 * development, and the Todo routes over a single shared in-memory repository.
 */
fun Application.todoModule() {
    val repository = TodoRepository()

    install(ContentNegotiation) {
        json(Json { prettyPrint = true })
    }

    // Dev-only permissive CORS: the React webapp is served from a different
    // origin (localhost:8080) than this API (localhost:8081), so the browser
    // requires CORS. Tighten `anyHost()` before any real deployment.
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }

    routing {
        todoRoutes(repository)
    }
}

/** Entry point: `./gradlew :sample:run` then open the webapp against :8081. */
fun main() {
    embeddedServer(Netty, port = TODO_SERVER_PORT, host = "0.0.0.0", module = Application::todoModule)
        .start(wait = true)
}
