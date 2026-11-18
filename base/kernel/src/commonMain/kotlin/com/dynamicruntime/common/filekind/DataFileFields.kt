package com.dynamicruntime.common.filekind

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.isDataFile
import com.dynamicruntime.common.util.toOptStr

/**
 * One file a value names (issue #1834): where in the value ([path], `files[2].clip` style), the id of the data file
 * record it names, and the field's own rule -- the kinds it takes and its largest size, read from [field].
 */
class DataFileUse(val path: String, val fileId: String, val field: SchType) {
    override fun toString(): String = "$path=$fileId"
}

/**
 * Finds the files a value names, by its type (issue #1834): every [SchType.isDataFile] field the value holds, however
 * deep -- in an object's fields, a map's entries, a list's items, or the branch of a union the value selects. What the
 * link between a form and its files is made from when the form is saved, and what a save is checked against.
 *
 * Read **by the type, never by the value's look**: a string that happens to look like a file id is not one unless its
 * field says so, and a field that names a file is found wherever the type puts it. A value that is not of its type's
 * shape is skipped where it differs, since validation, not this, is what refuses it.
 *
 * Theme: #DFILE.
 */
@Suppress("ConstPropertyName")
object DataFileFields {
    /** How deep [collect] goes before it refuses: the depth limit the codebase holds a walk of data to. */
    const val maxDepth = 30

    /** The files [value], a value of [type], names, in the order they are met. Empty when it names none. */
    fun collect(type: SchType, value: Any?, depth: Int = 0): List<DataFileUse> {
        val out = mutableListOf<DataFileUse>()
        walk(type, value, "", out, depth)
        return out
    }

    /** Whether [type] holds a file field anywhere: a type that holds none need not be walked at all. */
    fun holdsFiles(type: SchType, depth: Int = 0, seen: MutableSet<SchType> = mutableSetOf()): Boolean {
        if (depth > maxDepth) throw tooDeep()
        if (type.isDataFile()) return true
        // A type may refer to itself through a `$ref`, so one already being looked at says nothing new.
        if (!seen.add(type)) return false
        return type.properties.values.any { holdsFiles(it.valueType, depth + 1, seen) } ||
            type.itemType?.let { holdsFiles(it, depth + 1, seen) } == true ||
            type.additionalValueType?.let { holdsFiles(it, depth + 1, seen) } == true ||
            type.variants?.branches?.any { holdsFiles(it, depth + 1, seen) } == true
    }

    /**
     * [value], a value of [type], with every file field it holds taken out (issue #1838): an object's or a map's file
     * field dropped, a list's file items left out. What an import keeps of a form's data, since the files its fields
     * named are another deployment's, or another client's, and do not travel with it. A value naming no file comes back
     * as it was.
     */
    fun strip(type: SchType, value: Any?, depth: Int = 0): Any? {
        if (depth > maxDepth) throw tooDeep()
        return when (value) {
            is Map<*, *> -> {
                val variants = type.variants
                val shape = variants?.select(value[variants.discriminator].toOptStr()) ?: type
                val out = LinkedHashMap<String, Any?>()
                for ((key, entry) in value) {
                    val name = key.toOptStr() ?: continue
                    val fieldType = shape.properties[name]?.valueType ?: shape.additionalValueType
                    when {
                        fieldType == null -> out[name] = entry
                        fieldType.isDataFile() -> {}
                        else -> out[name] = strip(fieldType, entry, depth + 1)
                    }
                }
                out
            }
            is List<*> -> {
                val itemType = type.itemType ?: return value
                if (itemType.isDataFile()) emptyList<Any?>() else value.map { strip(itemType, it, depth + 1) }
            }
            else -> value
        }
    }

    private fun walk(type: SchType, value: Any?, path: String, out: MutableList<DataFileUse>, depth: Int) {
        if (depth > maxDepth) throw tooDeep()
        if (value == null) return
        if (type.isDataFile()) {
            // Only a string names a file: a number or a flag here is a value validation refuses, never an id.
            (value as? String)?.takeIf { it.isNotBlank() }?.let { out += DataFileUse(path, it, type) }
            return
        }
        when (value) {
            is Map<*, *> -> {
                // A union's value is described by the branch it selects, as the validator reads it.
                val variants = type.variants
                val shape = variants?.select(value[variants.discriminator].toOptStr()) ?: type
                for ((key, entry) in value) {
                    val name = key.toOptStr() ?: continue
                    val fieldType = shape.properties[name]?.valueType ?: shape.additionalValueType ?: continue
                    walk(fieldType, entry, if (path.isEmpty()) name else "$path.$name", out, depth + 1)
                }
            }
            is List<*> -> {
                val itemType = type.itemType ?: return
                value.forEachIndexed { i, item -> walk(itemType, item, "$path[$i]", out, depth + 1) }
            }
        }
    }

    private fun tooDeep(): KdrException =
        KdrException.mkConv("A value nests deeper than $maxDepth levels; its files cannot be found.")
}
