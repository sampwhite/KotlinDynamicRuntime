package com.dynamicruntime.sample.todo

import com.dynamicruntime.common.schema.JsonMappable

/**
 * Field keys shared by the Todo schema types ([TodoService.schema]) and the [Todo] record. Grouped in the
 * short upper-case object the code guide prescribes for constants; each constant's name matches its JSON key.
 */
@Suppress("ConstPropertyName")
object TD {
    const val id = "id"
    const val title = "title"
    const val completed = "completed"
    const val deleted = "deleted"
}

/**
 * A single todo item: both the in-memory record and the JSON shape the Todo endpoints return. It renders to
 * the wire as an explicit map ([toJsonMap]) rather than via reflection/serialization, per the code guide --
 * so no `@Serializable` or web-framework content negotiation is involved.
 */
data class Todo(
    val id: Int,
    val title: String,
    val completed: Boolean,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        TD.id to id,
        TD.title to title,
        TD.completed to completed,
    )
}
