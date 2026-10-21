package com.dynamicruntime.common.context

import com.dynamicruntime.common.app.DBGK
import com.dynamicruntime.common.app.DBGT
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.isVariableName

/**
 * One `_debug` word, declared once (issue #1334): the word a request sends, what it does, and which [DBGK] kind it is.
 * Declared beside the code that reads it, as an [EnvVarDef] is, and contributed by its component
 * (`ComponentDefinition.debugTags`), which is what puts it in the list the app bar's debug box offers.
 *
 * [KdrCxt.hasDebug] and [KdrCxt.hasDebugDiagnostic] take a def rather than a string, and refuse one no loaded component
 * contributed ([DebugTags.requireDeclared]). The code that checks a word runs in the tests of what surrounds it, so a
 * word left out of a component's list fails a test rather than going missing from the list.
 */
class DebugTagDef(
    /** The word, as a request sends it: a variable name, as `_debug` requires. */
    val name: String,
    /** What it does, in a sentence, for the person choosing it. */
    val description: String,
    /** Which of [DBGK]'s kinds it is. */
    val kind: String = DBGK.explain,
) {
    init {
        require(name.isVariableName()) { "A _debug word must be a variable name: '$name'." }
        require(kind in DBGK.all) { "Unknown _debug word kind '$kind' for '$name'." }
    }

    /** The word, so a def logs and interpolates as what is sent. */
    override fun toString(): String = name
}

/**
 * The `_debug` words this node's loaded components declare (issue #1334), gathered once at boot and kept in the
 * instance config.
 */
class DebugTags(val defs: List<DebugTagDef>) {
    private val byName: Map<String, DebugTagDef> = defs.associateBy { it.name }

    /**
     * Refuses a word no loaded component contributed. The check is by identity, so a second def spelling a declared
     * word is refused too: it would be a second description of one word.
     */
    fun requireDeclared(def: DebugTagDef) {
        if (byName[def.name] !== def) {
            throw KdrException(
                "The _debug word '${def.name}' is checked, but no loaded component declares it: add it to the " +
                    "debugTags of the component that owns it.",
            )
        }
    }

    /** The words as the app config lists them: each a map of [DBGT] fields, in the order the components gave them. */
    fun toWire(): List<Map<String, Any?>> = defs.map {
        linkedMapOf(DBGT.name to it.name, DBGT.description to it.description, DBGT.kind to it.kind)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Where the instance config keeps them. */
        const val registryKey = "kdr:debugTags"

        /** The words the components declared, refusing a word two of them declare. */
        fun of(defs: List<DebugTagDef>): DebugTags {
            val duplicate = defs.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.key
            if (duplicate != null) throw KdrException("The _debug word '$duplicate' is declared more than once.")
            return DebugTags(defs)
        }

        /** This instance's words, or null where none were gathered (a context built without booting an instance). */
        fun get(config: KdrInstanceConfig): DebugTags? = config.get(registryKey) as? DebugTags
    }
}
