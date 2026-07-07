package com.dynamicruntime.sample.todo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory store of todos, held by the per-instance [TodoService]. Thread-safe because the runtime serves
 * requests from a pool of (virtual) threads: a [ConcurrentHashMap] holds the items and an [AtomicInteger]
 * hands out unique ids. No persistence -- state resets when the server stops.
 */
class TodoRepository {
    private val items = ConcurrentHashMap<Int, Todo>()
    private val nextId = AtomicInteger(1)

    init {
        // A couple of seed items so the UI isn't empty on first load.
        add("Try the KDR endpoint framework")
        add("Render it with Ant Design and React")
    }

    /** All todos, ordered by id (i.e. creation order). */
    fun all(): List<Todo> = items.values.sortedBy { it.id }

    fun get(id: Int): Todo? = items[id]

    /** Creates a new, not-completed todo (with a trimmed title) and returns it. */
    fun add(title: String): Todo {
        val id = nextId.getAndIncrement()
        val todo = Todo(id = id, title = title.trim(), completed = false)
        items[id] = todo
        return todo
    }

    /**
     * Applies the non-null arguments to the todo with [id], leaving the rest unchanged. Returns the updated
     * todo, or null if no such todo exists.
     */
    fun update(id: Int, title: String?, completed: Boolean?): Todo? {
        return items.computeIfPresent(id) { _, current ->
            current.copy(
                title = title?.trim() ?: current.title,
                completed = completed ?: current.completed,
            )
        }
    }

    /** Removes the todo with [id]; returns true if one was actually removed. */
    fun delete(id: Int): Boolean = items.remove(id) != null
}
