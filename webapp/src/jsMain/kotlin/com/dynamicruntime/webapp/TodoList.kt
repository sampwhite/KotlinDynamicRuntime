package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for firing suspend API calls from React event handlers. */
private val todoScope = MainScope()

/**
 * Todo list UI backed by the `:sample` runtime's Todo endpoints (see [TodoApi]).
 *
 * Demonstrates the full add / edit / toggle-complete / delete cycle against the
 * in-memory server, rendered with Ant Design components. All mutations re-fetch
 * the list from the server so the UI always reflects authoritative state.
 */
val TodoList = FC<Props> {
    var todos by useState(emptyList<Todo>())
    var newTitle by useState("")
    var editingId by useState<Int?>(null)
    var editTitle by useState("")
    var error by useState<String?>(null)

    // Runs [block] in the coroutine scope, surfacing failures (e.g. the API
    // server not running) as an on-screen error instead of an unhandled reject.
    fun run(block: suspend () -> Unit) {
        todoScope.launch {
            try {
                block()
                error = null
            } catch (e: Throwable) {
                error = "API call failed — is `./gradlew :sample:run` running? (${e.message})"
            }
        }
    }

    fun reload() = run { todos = TodoApi.list() }

    useEffectOnce { reload() }

    div {
        className = ClassName("card")

        h1 { +"Todo list" }
        p {
            className = ClassName("subtitle")
            +"Add / edit / complete / delete, served by the :sample runtime's endpoints and rendered with Ant Design."
        }

        // --- Add row ---
        div {
            className = ClassName("row")
            Input {
                placeholder = "Add a todo…"
                value = newTitle
                onChange = { event -> newTitle = event.target.value as String }
                onPressEnter = {
                    if (newTitle.isNotBlank()) run {
                        TodoApi.add(newTitle); newTitle = ""; todos = TodoApi.list()
                    }
                }
            }
            Button {
                type = "primary"
                disabled = newTitle.isBlank()
                onClick = {
                    run { TodoApi.add(newTitle); newTitle = ""; todos = TodoApi.list() }
                }
                +"Add"
            }
        }

        // --- Items ---
        todos.forEach { todo ->
            div {
                className = ClassName("row")

                Checkbox {
                    checked = todo.completed
                    onChange = {
                        run {
                            TodoApi.update(todo.id, completed = !todo.completed)
                            todos = TodoApi.list()
                        }
                    }
                }

                if (editingId == todo.id) {
                    Input {
                        value = editTitle
                        onChange = { event -> editTitle = event.target.value as String }
                        onPressEnter = {
                            run {
                                TodoApi.update(todo.id, title = editTitle)
                                editingId = null; todos = TodoApi.list()
                            }
                        }
                    }
                    Button {
                        type = "primary"
                        size = "small"
                        onClick = {
                            run {
                                TodoApi.update(todo.id, title = editTitle)
                                editingId = null; todos = TodoApi.list()
                            }
                        }
                        +"Save"
                    }
                    Button {
                        size = "small"
                        onClick = { editingId = null }
                        +"Cancel"
                    }
                } else {
                    span {
                        className = ClassName(if (todo.completed) "todo-title done" else "todo-title")
                        +todo.title
                    }
                    Button {
                        size = "small"
                        onClick = { editingId = todo.id; editTitle = todo.title }
                        +"Edit"
                    }
                    Button {
                        size = "small"
                        danger = true
                        onClick = {
                            run { TodoApi.delete(todo.id); todos = TodoApi.list() }
                        }
                        +"Delete"
                    }
                }
            }
        }

        error?.let {
            p {
                className = ClassName("todo-error")
                +it
            }
        }
    }
}
