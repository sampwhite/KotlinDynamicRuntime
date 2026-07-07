package com.dynamicruntime.kdn.demo

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.toJsonMap

/**
 * Demo field keys and choice values. Schema keys, so each constant's name matches its value
 * (per the code guide); referenced qualified, e.g. `DMO.name`.
 */
@Suppress("ConstPropertyName")
object DMO {
    // Field keys.
    const val name = "name"
    const val style = "style"
    const val repeat = "repeat"
    const val shout = "shout"
    const val message = "message"
    const val a = "a"
    const val b = "b"
    const val op = "op"
    const val expression = "expression"
    const val value = "value"
    const val n = "n"
    const val status = "status"
    const val contains = "contains"
    const val id = "id"
    const val title = "title"
    const val done = "done"

    // Greeting styles.
    const val formal = "formal"
    const val casual = "casual"
    const val enthusiastic = "enthusiastic"

    // Calculator operations.
    const val add = "add"
    const val subtract = "subtract"
    const val multiply = "multiply"
    const val divide = "divide"

    // Todo status filters (`all`, plus the two `done` states).
    const val all = "all"
    const val open = "open"
}

/**
 * A handful of illustrative endpoints, contributed by [com.dynamicruntime.kdn.KdnComponent], that give
 * the portal UI something with real input to render: required fields, `options` selects, numeric and
 * boolean inputs, a GET whose fields ride in the query string, POST bodies, a list endpoint, and inputs
 * that deliberately fail validation or the handler so error responses are visible too.
 *
 * Pure demo: no persistence, no service lifecycle -- just schema plus plain handler lambdas, matching the
 * "handler is a plain lambda" endpoint style. The todo list is a fixed in-memory sample.
 */
object DemoEndpoints {

    /** Fixed in-memory sample data for the list endpoint. */
    private val todos: List<Map<String, Any?>> = listOf(
        linkedMapOf(DMO.id to 1, DMO.title to "Write the schema parser", DMO.done to true),
        linkedMapOf(DMO.id to 2, DMO.title to "Wire up the request dispatcher", DMO.done to true),
        linkedMapOf(DMO.id to 3, DMO.title to "Build the endpoint portal", DMO.done to true),
        linkedMapOf(DMO.id to 4, DMO.title to "Add sample endpoints", DMO.done to false),
        linkedMapOf(DMO.id to 5, DMO.title to "Port the auth subsystem", DMO.done to false),
    )

    fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "demo") {
        // --- POST /demo/greeting : required string + select + int(default) + boolean --------------
        type("GreetingRequest") {
            type = SCT.kObject
            property(DMO.name, "Who to greet.", required = true)
            property(DMO.style, "Tone of the greeting.") {
                option(DMO.formal); option(DMO.casual); option(DMO.enthusiastic)
            }
            // Required-with-default: when omitted, coercion injects the default (the framework only
            // injects defaults for required properties), so the handler can rely on it being present.
            property(DMO.repeat, "How many times to repeat the greeting (1-20).", required = true) {
                type = SCT.integer
                default = 3
            }
            property(DMO.shout, "Whether to shout the greeting in upper case.") { type = SCT.boolean }
        }
        type("Greeting") {
            type = SCT.kObject
            property(DMO.message, "The composed greeting.", required = true)
        }
        generalEndpoint("/demo/greeting", "Compose a greeting from a name, tone, repeat count, and a shout flag.", HttpMethod.POST, outputRef = "Greeting", inputRef = "GreetingRequest") { _, request ->
            greeting(request)
        }

        // --- POST /demo/calc : two required numbers + required select; can 400 on divide-by-zero ---
        type("CalcRequest") {
            type = SCT.kObject
            property(DMO.a, "The first operand.", required = true) { type = SCT.number }
            property(DMO.b, "The second operand.", required = true) { type = SCT.number }
            property(DMO.op, "The operation to apply.", required = true) {
                option(DMO.add); option(DMO.subtract); option(DMO.multiply); option(DMO.divide)
            }
        }
        type("CalcResult") {
            type = SCT.kObject
            property(DMO.expression, "The evaluated expression.", required = true)
            property(DMO.value, "The result.", required = true) { type = SCT.number }
        }
        generalEndpoint("/demo/calc", "Evaluate a two-operand arithmetic operation.", HttpMethod.POST, outputRef = "CalcResult", inputRef = "CalcRequest") { _, request ->
            calc(request)
        }

        // --- GET /demo/fibonacci : a GET whose single required integer rides in the query string ---
        type("FibRequest") {
            type = SCT.kObject
            property(DMO.n, "Which Fibonacci number to compute (0-92).", required = true) { type = SCT.integer }
        }
        type("FibResult") {
            type = SCT.kObject
            property(DMO.n, "The requested index.", required = true) { type = SCT.integer }
            property(DMO.value, "The nth Fibonacci number.", required = true) { type = SCT.integer }
        }
        generalEndpoint("/demo/fibonacci", "Compute the nth Fibonacci number (input rides in the query string).", HttpMethod.GET, outputRef = "FibResult", inputRef = "FibRequest") { _, request ->
            fibonacci(request)
        }

        // --- POST /demo/todos : a list endpoint whose request (with a select) nests under `request` --
        type("TodoQuery") {
            type = SCT.kObject
            property(DMO.status, "Filter by completion status.") {
                default = DMO.all
                option(DMO.all); option(DMO.open); option(DMO.done)
            }
            property(DMO.contains, "Only include todos whose title contains this text.")
        }
        type("Todo") {
            type = SCT.kObject
            property(DMO.id, "Stable id.", required = true) { type = SCT.integer }
            property(DMO.title, "What needs doing.", required = true)
            property(DMO.done, "Whether it is complete.", required = true) { type = SCT.boolean }
        }
        // POST (not the usual GET) so the portal can submit the nested `request` object as a JSON body.
        listEndpoint("/demo/todos", "List sample todos, filtered by status and title text.", outputRef = "Todo", method = HttpMethod.POST, inputRef = "TodoQuery") { _, request ->
            queryTodos(request)
        }
    }

    // --- handlers ---------------------------------------------------------------------------------

    private fun greeting(request: Map<String, Any?>): Map<String, Any?> {
        val name = (request[DMO.name] as? String).orEmpty().ifBlank { "there" }
        val repeat = ((request[DMO.repeat] as? Number)?.toInt() ?: 1).coerceIn(1, 20)
        val shout = request[DMO.shout] as? Boolean ?: false
        val base = when (request[DMO.style] as? String) {
            DMO.formal -> "Good day, $name."
            DMO.enthusiastic -> "Hey hey, $name!!"
            else -> "Hi $name." // casual / unspecified
        }
        val composed = List(repeat) { base }.joinToString(" ")
        return linkedMapOf(DMO.message to if (shout) composed.uppercase() else composed)
    }

    private fun calc(request: Map<String, Any?>): Map<String, Any?> {
        val a = (request[DMO.a] as Number).toDouble()
        val b = (request[DMO.b] as Number).toDouble()
        val op = request[DMO.op] as String
        val value = when (op) {
            DMO.add -> a + b
            DMO.subtract -> a - b
            DMO.multiply -> a * b
            DMO.divide -> if (b == 0.0) throw KdrException.mkInput("Cannot divide by zero.") else a / b
            else -> throw KdrException.mkInput("Unknown operation '$op'.")
        }
        val symbol = mapOf(DMO.add to "+", DMO.subtract to "-", DMO.multiply to "*", DMO.divide to "/").getValue(op)
        return linkedMapOf(DMO.expression to "$a $symbol $b", DMO.value to value)
    }

    private fun fibonacci(request: Map<String, Any?>): Map<String, Any?> {
        val n = (request[DMO.n] as Number).toInt()
        if (n < 0) throw KdrException.mkInput("n must be zero or greater.")
        if (n > 92) throw KdrException.mkInput("n must be 92 or less to fit in a 64-bit integer.")
        var prev = 0L
        var cur = 1L
        repeat(n) {
            val next = prev + cur
            prev = cur
            cur = next
        }
        return linkedMapOf(DMO.n to n, DMO.value to prev)
    }

    private fun queryTodos(request: Map<String, Any?>): List<Map<String, Any?>> {
        val query = (request[EP.request] as? Map<*, *>)?.toJsonMap() ?: emptyMap()
        val status = query[DMO.status] as? String ?: DMO.all
        val needle = (query[DMO.contains] as? String)?.trim()?.lowercase().orEmpty()
        return todos.filter { todo ->
            val isDone = todo[DMO.done] as Boolean
            val statusOk = when (status) {
                DMO.open -> !isDone
                DMO.done -> isDone
                else -> true
            }
            val containsOk = needle.isEmpty() || (todo[DMO.title] as String).lowercase().contains(needle)
            statusOk && containsOk
        }
    }
}
