package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.naming.OWNR
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH

/**
 * The **portable form** of a client's stored configuration (issue #1435): its data with the client's own namespace,
 * `client.<id>`, written as [namespace], `client.@self`, so a bundle read from one client loads in another -- or on
 * another instance -- as it is. The backend resolves the placeholder when a write arrives ([fromPortable]) and writes
 * it when a read asks ([toPortable]), so the form is the API's, usable by any caller.
 *
 * ### What is rewritten
 *
 * A client's stored configuration names its client in one way only: its namespace, where its types live (issue
 * #949). That name is found in a bundle's `namespace`, a trait's or schema type's `typeName`, a `$ref` inside a type's
 * schema, and a key of a workflow definition's `types` map -- an inventory taken over every slot. Rather than list
 * those places, a value is rewritten when it **is** such a name, whole: the namespace, a name beneath it
 * (`client.acme.Memo`, `client.acme.forms.Memo`), or a `$defs` pointer to one. So a slot that comes to store a type name
 * is covered with no change here, and free text is never touched: a description mentioning `client.acme.Memo` keeps
 * it. Another client's namespace -- a template's types, say -- and global names stay as they are.
 *
 * Every slot is rewritten but [keptSlots], whose entries say who the client is rather than what it declares.
 */
@Suppress("ConstPropertyName")
object PORT {
    /** What stands for the client in portable form: `@self`, which no client id can be, since an id is letters and digits. */
    const val self = "@self"

    /** The portable client namespace: `client.@self`. */
    const val namespace = "${OWNR.clientRoot}.$self"

    /** On a bundle read: `true` to answer in portable form. */
    const val portableField = "portable"

    /** Slots left as they are either way: the client's definition, and the customers only `hub` declares. */
    val keptSlots: Set<String> = setOf(CCT.clientDef, CCT.customerDef)
}

/** [value] -- a bundle's slots, an entry, or any part of one -- with [client]'s own namespace written as [PORT.namespace]. */
fun toPortable(value: Any?, client: String): Any? = renamed(value, clientNamespace(client), PORT.namespace)

/** [value] in portable form, with [PORT.namespace] resolved to [client]'s own namespace: [toPortable]'s inverse. */
fun fromPortable(value: Any?, client: String): Any? = renamed(value, PORT.namespace, clientNamespace(client))

/** A bundle's [slots] in portable form for [client], each slot rewritten but [PORT.keptSlots]. */
fun slotsToPortable(slots: Map<String, List<Map<String, Any?>>>, client: String): Map<String, List<Map<String, Any?>>> =
    slots.mapValues { (slot, entries) -> if (slot in PORT.keptSlots) entries else entries.map { portableEntry(it, client, toward = true) } }

/** A bundle's [slots] in portable form, resolved for [client]: [slotsToPortable]'s inverse. */
fun slotsFromPortable(slots: Map<String, List<Map<String, Any?>>>, client: String): Map<String, List<Map<String, Any?>>> =
    slots.mapValues { (slot, entries) -> if (slot in PORT.keptSlots) entries else entries.map { portableEntry(it, client, toward = false) } }

/** One entry of a slot that is rewritten, toward portable form or back. */
@Suppress("UNCHECKED_CAST")
private fun portableEntry(entry: Map<String, Any?>, client: String, toward: Boolean): Map<String, Any?> =
    (if (toward) toPortable(entry, client) else fromPortable(entry, client)) as Map<String, Any?>

/** [value] with every name that is [from], or beneath it, or a `$defs` pointer to either, renamed to [to]. */
private fun renamed(value: Any?, from: String, to: String): Any? = when (value) {
    is Map<*, *> -> value.entries.associate { (k, v) -> (if (k is String) renamedName(k, from, to) else k) to renamed(v, from, to) }
    is List<*> -> value.map { renamed(it, from, to) }
    is String -> renamedName(value, from, to)
    else -> value
}

private const val defsPointer = "#/${SCH.dDefs}/"

/** [s] renamed when it is [from] whole, a name beneath it, or a `$defs` pointer to one of those; otherwise [s]. */
private fun renamedName(s: String, from: String, to: String): String {
    if (s.startsWith(defsPointer)) {
        val target = s.substring(defsPointer.length)
        val named = renamedName(target, from, to)
        return if (named == target) s else defsPointer + named
    }
    return when {
        s == from -> to
        s.startsWith(from) && s[from.length] == OWNR.namespaceSep -> to + s.substring(from.length)
        else -> s
    }
}
