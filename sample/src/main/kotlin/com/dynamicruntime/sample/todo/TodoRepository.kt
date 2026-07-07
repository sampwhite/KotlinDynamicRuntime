package com.dynamicruntime.sample.todo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory store of todos. Thread-safe because Netty serves requests from a
 * pool of threads: a [ConcurrentHashMap] holds the items and an [AtomicInteger]
 * hands out unique ids. No persistence — state resets when the server stops.
 */
class TodoRepository {
    private val items = ConcurrentHashMap<Int, Todo>()
    private val nextId = AtomicInteger(1)

    init {
        // A couple of seed items so the UI isn't empty on first load.
        add(CreateTodoRequest("Try the Ktor todo API"))
        add(CreateTodoRequest("Wire it into the React UI"))
    }

    /** All todos, ordered by id (i.e. creation order). */
    fun all(): List<Todo> = items.values.sortedBy { it.id }

    fun get(id: Int): Todo? = items[id]

    /** Creates a new, not-completed todo and returns it. */
    fun add(request: CreateTodoRequest): Todo {
        val id = nextId.getAndIncrement()
        val todo = Todo(id = id, title = request.title.trim(), completed = false)
        items[id] = todo
        return todo
    }

    /**
     * Applies the non-null fields of [request] to the todo with [id]. Returns the
     * updated todo, or null if no such todo exists.
     */
    fun update(id: Int, request: UpdateTodoRequest): Todo? {
        return items.computeIfPresent(id) { _, current ->
            current.copy(
                title = request.title?.trim() ?: current.title,
                completed = request.completed ?: current.completed,
            )
        }
    }

    /** Removes the todo with [id]; returns true if one was actually removed. */
    fun delete(id: Int): Boolean = items.remove(id) != null
}
