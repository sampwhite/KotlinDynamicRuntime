package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.toJsonMap

/** Names of the generated meta-schema (issue #1056): its type, where it is served, and what it is served as. */
@Suppress("ConstPropertyName")
object MSCH {
    /** Where the meta-schema is served: beside the endpoint catalog, which is read by the same people. */
    const val path = "/schema/metaSchema"

    /** The one type the meta-schema declares: a schema node, as this layer reads one. */
    const val nodeTypeName = "kdr.meta.SchemaNode"

    /** Response field: the name, in `$defs`, of the type a schema body is validated against. */
    const val nodeType = "nodeType"

    /** Response field: the rules of the dialect the document cannot state, each a [rule] over [keywords]. */
    const val notStated = "notStated"

    /** Response field: what no shape decides, in words -- why a body that passes is not thereby valid. */
    const val notChecked = "notChecked"

    const val rule = "rule"
    const val keywords = "keywords"
    const val detail = "detail"

    /** [rule]: a key carrying our prefix is refused unless it is one of [keywords]; any other unknown key passes. */
    const val closedPrefix = "closedPrefix"

    /** [rule]: a standard keyword this layer refuses by name. */
    const val refusedKeyword = "refusedKeyword"

    /** [rule]: a keyword whose value is one shape or another, which the document leaves untyped. */
    const val eitherOf = "eitherOf"

    /** [rule]: a keyword read only at the top of a type a configuration declares, and refused anywhere else. */
    const val placement = "placement"

    /** [rule]: a keyword whose entries may be null -- a name not set -- where the document says each is a schema. */
    const val unsetEntry = "unsetEntry"

    /** How deep a schema body's nodes are followed. Matches the JSON nesting cap, so a parsed document cannot pass it. */
    const val maxDepth = 50
}

/**
 * The dialect's **schema for schema** (issue #1056), generated from the keyword tables the parser reads --
 * [SchStdKeywords] for the standard keywords this layer holds to a shape, [SchGKeywords] for our own -- so a
 * keyword's shape is stated once, in its table, and this can never be a second opinion about it.
 *
 * That is the answer to the objection this layer made to a schema for schema, in writing: a hand-kept one "would be
 * a large `$defs` pile to keep in step with every keyword, and subtly wrong the first time somebody forgot". There
 * is no pile to keep: add a keyword to a table and it is here.
 *
 * **Two things are made from the tables**, and they are not the same thing:
 * - [structureFailures] **walks** a schema body and reports every fault of shape in it, each by its path. It is
 *   exact -- it asks the tables themselves -- and it is what a write is held to.
 * - [defs] is the **document**: the same shapes written in our own dialect, for a reader, an editor's form, a test.
 *   Our dialect cannot say three things a schema node needs said -- "this or that", "no key with our prefix but
 *   these", "this keyword must not appear" -- so the document types what it can and [notStated] lists the rest.
 *   A body the document accepts may still be one the walk refuses. The reverse happens in one named case: a
 *   property set to null, which is how an alteration removes one and which our dialect has no way to allow
 *   beside a schema.
 *
 * **Neither is validity.** A schema node is an open record: the keywords we read are typed and anything else is
 * the document's own. What a reference resolves to, whether a keyword applies to the type it sits on, what a
 * client may narrow -- [notChecked] -- stay the parser's and the trial reload's, with their own wording. This is the
 * first pass, which finds every fault of shape at once where the parser stops at its first.
 */
object SchMetaSchema {
    private val nodeRef = mapOf(SCH.dRef to "#/${SCH.dDefs}/${MSCH.nodeTypeName}")

    /**
     * The meta-schema as `$defs` content, in our dialect: one type, [MSCH.nodeTypeName], an open object declaring
     * every keyword of both tables. A keyword our dialect cannot type ([SchShapeForm.Either]) is declared untyped,
     * with its shape in its description.
     */
    val defs: Map<String, Any?> by lazy {
        val properties = LinkedHashMap<String, Any?>()
        for ((keyword, shape) in keywordShapes) {
            properties[keyword] = stated(shape.form).orEmpty() + (SCH.description to "Must be ${shape.described}.")
        }
        mapOf(
            MSCH.nodeTypeName to linkedMapOf(
                SCH.type to SCT.kObject,
                SCH.description to "A schema, as this layer reads one: each keyword it interprets, held to its shape. " +
                    "Any other key is the document's own and passes -- except one beginning '${SCH.gPrefix}', and the " +
                    "standard keywords this layer refuses; see '${MSCH.notStated}'.",
                SCH.additionalProperties to true,
                SCH.properties to properties,
            ),
        )
    }

    /** [defs], compiled. */
    val types: Map<String, SchType> by lazy { parseSchemaTypes(defs) }

    /** The type a schema body is validated against: [MSCH.nodeTypeName]. */
    val nodeType: SchType get() = types.getValue(MSCH.nodeTypeName)

    /**
     * The rules of the dialect that [defs] does not state, generated from the same tables: each a
     * `{rule, keywords, detail}`. What [structureFailures] enforces and the document cannot say.
     */
    val notStated: List<Map<String, Any?>> by lazy {
        fun rule(rule: String, keywords: List<String>, detail: String) =
            linkedMapOf(MSCH.rule to rule, MSCH.keywords to keywords, MSCH.detail to detail)
        buildList {
            add(
                rule(
                    MSCH.closedPrefix, SchGKeywords.keywords.sorted(),
                    "A key beginning '${SCH.gPrefix}' is ours, and the list of them is closed: one that is not among " +
                        "these is refused as a misspelling. Any other key this layer does not read passes.",
                ),
            )
            refusedKeywords.forEach { (keyword, advice) -> add(rule(MSCH.refusedKeyword, listOf(keyword), "Not supported. $advice")) }
            keywordShapes.filterValues { it.form is SchShapeForm.Either }.forEach { (keyword, shape) ->
                add(rule(MSCH.eitherOf, listOf(keyword), "Must be ${shape.described}; the document leaves its value untyped."))
            }
            keywordShapes.filterValues { it.form == SchShapeForm.NodeMap }.keys.forEach { keyword ->
                add(
                    rule(
                        MSCH.unsetEntry, listOf(keyword),
                        "An entry may be null, which is a name not set -- how a client's alteration of a global type " +
                            "removes one. The document says every entry is a schema, so it refuses a null there.",
                    ),
                )
            }
            add(
                rule(
                    MSCH.placement, SchGKeywords.directives.sorted(),
                    "Read where a client's configuration assembles its types -- at the top of a type it declares -- " +
                        "and refused anywhere else, whatever the value.",
                ),
            )
        }
    }

    /** What no shape decides, in words: why a body that passes is not thereby a valid schema. */
    val notChecked: List<String> = listOf(
        "Whether a '${SCH.dRef}' names a type that exists, and whose namespace a type may be declared in.",
        "Whether a keyword applies to the type it sits on, and what it means there: a '${SCH.pattern}' on an " +
            "integer, a '${SCH.oneOf}' with no '${SCH.discriminator}', a bound below its partner.",
        "The keywords read more closely than a shape: '${SCH.pattern}', '${SCH.const}', '${SCH.default}', " +
            "'${SCH.uniqueItems}', the exclusive bounds, '${SCH.kIf}'/'${SCH.kThen}'/'${SCH.kElse}', '${SCH.discriminator}'.",
        "Whether '${SCH.required}' names properties the type declares.",
        "What a client's alteration of a global type may change: it may narrow, never widen.",
        "The content of '${SCH.errors}', '${SCH.layout}' and '${SCH.options}', each with a vocabulary of its own.",
    )

    /**
     * Every fault of **shape** in the schema [body], each a failure at the path of the keyword at fault
     * (`properties.cost.type`) below [at] -- empty when there is none. At every schema node in the body: a keyword
     * this layer refuses, a keyword it reads whose value is not of its shape, a key with our prefix that is not
     * one of ours. A node is wherever the tables say one sits ([SchShapeForm.nodesIn]): a property, an `items`, a
     * map's value schema, a `oneOf` branch.
     *
     * [directivesStandAtTop] is for a body a configuration **stores**, whose top may carry a merge or an extension
     * for the assembly to read; whether it may there is judged with the configuration, not here. Anywhere else,
     * and without the flag, a directive is the fault the parser calls it.
     *
     * The wording is the tables' own, so a fault reads the same here, from the parser, and on a client's issue list.
     */
    fun structureFailures(body: Map<String, Any?>, at: String = "", directivesStandAtTop: Boolean = false): List<SchFailure> {
        val out = mutableListOf<SchFailure>()
        walk(body, at, directivesStandAtTop, 0, out)
        return out
    }

    private fun walk(node: Map<String, Any?>, at: String, directivesStand: Boolean, depth: Int, out: MutableList<SchFailure>) {
        if (depth > MSCH.maxDepth) {
            throw KdrException.mkInput("The schema at '$at' nests deeper than ${MSCH.maxDepth} levels.")
        }
        for ((key, value) in node) {
            val keyPath = childPath(at, key)
            keywordProblem(key, value, directivesStand)?.let { out.add(SchFailure(keyPath, SchFailCode.badValue, it.message)) }
            // Into whatever of the value is a schema node, well shaped as a whole or not: a property declared as a
            // number does not excuse the faults in the property beside it.
            for ((below, child) in SchStdKeywords.nodesIn(key, value)) {
                walk(child.toJsonMap(), keyPath + below, directivesStand = false, depth + 1, out)
            }
        }
    }

    /** What is wrong with [key] set to [value] in a schema node, by the tables; null when nothing is. */
    private fun keywordProblem(key: String, value: Any?, directivesStand: Boolean): Problem? =
        refusedKeywordProblem(subject, key)
            ?: SchStdKeywords.problem(subject, key, value)
            ?: SchGKeywords.problem(subject, key, value, directivesStand)

    /** How a failure names what it is about: its path says where, so the message need only say "this". */
    private const val subject = "This schema"

    private val keywordShapes: Map<String, SchKeywordShape> get() = SchStdKeywords.entries + SchGKeywords.entries

    /** [form] in our dialect, or null for one it has no keyword for. */
    private fun stated(form: SchShapeForm): Map<String, Any?>? = when (form) {
        // Strict: a boolean coerces from text by default, and `"yes"` is exactly what a keyword's shape refuses.
        SchShapeForm.Flag -> mapOf(SCH.type to SCT.boolean, SCH.allowCoerce to false)
        SchShapeForm.Text -> mapOf(SCH.type to SCT.string)
        // A number coerces from text that spells one by default, which is the shape.
        SchShapeForm.NumberLike -> mapOf(SCH.type to SCT.number)
        SchShapeForm.Obj -> mapOf(SCH.type to SCT.kObject)
        SchShapeForm.Node -> nodeRef
        SchShapeForm.NodeMap -> mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to nodeRef)
        SchShapeForm.Nodes -> mapOf(SCH.type to SCT.array, SCH.items to nodeRef)
        is SchShapeForm.Choice -> mapOf(SCH.type to SCT.string, SCH.options to form.values)
        is SchShapeForm.ListOf -> mapOf(SCH.type to SCT.array) + (form.of?.let { stated(it) }?.let { mapOf(SCH.items to it) }.orEmpty())
        is SchShapeForm.Either -> null
    }
}
