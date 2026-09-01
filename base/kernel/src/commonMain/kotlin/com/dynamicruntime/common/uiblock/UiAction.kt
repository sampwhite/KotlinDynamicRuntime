package com.dynamicruntime.common.uiblock

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HACT

/**
 * One frontend function a UiBlock may name (issue #483): its name, and how many parameters it takes.
 *
 * **Declared in the kernel because neither side can check the other.** The reference is authored in
 * backend data and the implementation is a frontend artifact, so the vocabulary has to be shared code or a
 * misspelled name is a click that silently does nothing. See `ui-block.md`.
 *
 * [arity] is here rather than left to the implementation because it is what the **backend** validates against:
 * an array's length is readable without parsing, so a wrong parameter count becomes a boot failure instead of
 * an undefined argument at click time.
 */
class UiActionDef(val name: String, val arity: Int = 0) {
    override fun toString(): String = if (arity == 0) name else "$name/$arity"
}

/**
 * What a UiBlock item does when it is chosen: go somewhere, or call something (issue #483).
 *
 * **One field on the wire, two shapes.** A string is a route; an array is a call. That is not tidiness: two
 * nullable fields (`page` and `action`, as this replaced) admit "both" and "neither", and once the item is
 * configuration rather than Kotlin nothing catches either. One field cannot express both.
 *
 * The JSON **type** is the discriminator, so no sigil is needed, and there is no delimiter for a parameter to
 * collide with -- which a delimited form (`f:name/a/b`) would have needed a rule for. It is a legitimate union
 * rather than a shortcut past "declare the discriminator": that rule is about telling *object* variants apart,
 * where nothing in the JSON says which branch you are in, while here a validator checks the type directly.
 */
sealed interface UiAction {
    /** This action as it travels: a string, or a list whose head is the function name. */
    fun toJson(): Any
}

/** Go to a frontend page. The frontend maps the id onto its own routing. */
class UiRoute(val page: String) : UiAction {
    override fun toJson(): Any = page
    override fun toString(): String = page
}

/**
 * Call a registered frontend function with [args].
 *
 * The parameter count is checked **here**, where the call is written, rather than left for the boot check to
 * find later -- the earliest moment it is knowable is the moment somebody writes it wrong.
 */
class UiCall(val def: UiActionDef, val args: List<String> = emptyList()) : UiAction {
    init {
        if (args.size != def.arity) {
            throw KdrException.mkConv(
                "The frontend function '${def.name}' takes ${def.arity} parameter(s) and was given " +
                    "${args.size}. The registry declares the count so a mismatch is a refusal here rather " +
                    "than an undefined argument when somebody clicks.",
            )
        }
    }

    override fun toJson(): Any = listOf(def.name) + args
    override fun toString(): String = toJson().toString()
}

/**
 * Reads an action back off the wire, or null when there is none (issue #483).
 *
 * In the kernel beside the writing half so backend and frontend share **one** interpretation of the union. Two
 * readings of a two-shaped field is the drift this construct would otherwise invite: the side that writes it
 * and the side that acts on it disagreeing about what an empty array, or a number, meant.
 *
 * A malformed value reads as null rather than throwing. It is data arriving over HTTP into a rendering path,
 * and a shell that fails to draw is a worse answer than a menu item that does nothing -- the boot check is what
 * stops a malformed one being served in the first place.
 */
fun parseUiAction(value: Any?): UiAction? = when (value) {
    is String -> value.takeIf { it.isNotEmpty() }?.let { UiRoute(it) }
    is List<*> -> {
        val name = value.firstOrNull() as? String
        if (name.isNullOrEmpty()) null
        else UiCall(UiActionDef(name, value.size - 1), value.drop(1).map { it?.toString() ?: "" })
    }
    else -> null
}

/**
 * Every frontend function a UiBlock may name (issue #483).
 *
 * **Hardwired, and that is the point rather than a stage it grows out of.** A configuration may only ever name
 * a function a developer wrote, which is what keeps *extensibility through data* from becoming *code in data*.
 * A module that needs an action of its own adds it here; that edit is the review.
 *
 * A plain list rather than a collected registry because both sides need it and only one side runs a boot: the
 * backend checks references against it, and the frontend asserts that its implementations cover it. That second
 * check is the only guard for "declared and referenced but never implemented", which is otherwise a click that
 * does nothing.
 */
object UiActions {
    val declared: List<UiActionDef> = listOf(HACT.logout, HACT.envLogout, HACT.openPath, HACT.setEnvDebug)

    /** The declaration for [name], or null when nothing declares it. */
    fun forName(name: String): UiActionDef? = declared.firstOrNull { it.name == name }
}
