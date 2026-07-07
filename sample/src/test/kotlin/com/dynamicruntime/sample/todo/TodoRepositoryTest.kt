package com.dynamicruntime.sample.todo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class TodoRepositoryTest : StringSpec({

    "a fresh repository starts with the two seed todos" {
        val repo = TodoRepository()
        val all = repo.all()
        all.map { it.title } shouldBe listOf("Try the KDR endpoint framework", "Render it with Ant Design and React")
        all.all { !it.completed } shouldBe true
    }

    "add creates a not-completed todo with a fresh id and trims the title" {
        val repo = TodoRepository()
        val created = repo.add("  Buy milk  ")

        created.title shouldBe "Buy milk"
        created.completed shouldBe false
        repo.get(created.id) shouldBe created
    }

    "update applies only the provided fields, leaving the rest unchanged" {
        val repo = TodoRepository()
        val todo = repo.add("Original")

        val completed = repo.update(todo.id, title = null, completed = true)
        completed?.title shouldBe "Original"
        completed?.completed shouldBe true

        val renamed = repo.update(todo.id, title = "Renamed", completed = null)
        renamed?.title shouldBe "Renamed"
        // completion set by the previous update is preserved.
        renamed?.completed shouldBe true
    }

    "update of a missing id returns null" {
        val repo = TodoRepository()
        repo.update(9999, title = "nope", completed = null).shouldBeNull()
    }

    "delete removes the todo and reports whether one was removed" {
        val repo = TodoRepository()
        val todo = repo.add("Temporary")

        repo.delete(todo.id) shouldBe true
        repo.get(todo.id).shouldBeNull()
        // A second delete of the same id finds nothing to remove.
        repo.delete(todo.id) shouldBe false
    }
})
