package com.dynamicruntime.common.uiblock

import com.dynamicruntime.common.exception.KdrException

/** Well-known keys inside a UiBlock's JSON, and the ordering convention. See `ui-block.md`. */
@Suppress("ConstPropertyName")
object UIB {
    /**
     * The condition deciding whether an object is shown -- a cfact **expression** (issue #454).
     *
     * Read **recursively**: any object carrying this key is dropped when its expression does not match, at
     * whatever depth it sits. One rule rather than a schema of where conditions may appear, which is what lets
     * a UiBlock grow new shapes without the resolver learning them.
     *
     * Named for the expression rather than for the cfact, and the distinction earns its keep twice. A single
     * name *is* a valid expression, so `"cfact": "hasAdminLevel"` would read correctly while
     * `"cfact": "(app,loggedIn)|isDeploymentOperator"` plainly would not -- that is not *a* cfact. And it
     * leaves `cfacts` free for what is actually coming: a set of cfact **names** contributed about the thing
     * being rendered, which `CFactRegistry.assemble` already takes as `targetFacts`. One word meaning a name
     * in one place and an expression in another is a collision worth a longer key to avoid.
     *
     * A generic name (`condition`, `when`) was the other candidate and is worse: a field called `condition`
     * invites somebody to write `count > 3` in it, where a field with `cfact` in its name says the vocabulary
     * is closed.
     */
    const val cfactExpression = "cfactExpression"

    /**
     * Where an item sorts within its array. Conventionally spaced by [orderStep], so a later contributor can
     * land between two existing items without anybody renumbering.
     */
    const val displayOrder = "displayOrder"

    /** The spacing convention for [displayOrder]: room for 99 later arrivals between any two items. */
    const val orderStep = 100

    /** Instance-config key under which the boot collects every contributed [UiBlockSource]. */
    const val registryKey = "uiBlockSources"
}

/**
 * One contribution to a UiBlock -- a **layer**, exactly as a fragment has layers (issue #457).
 *
 * A **base** supplies the block and declares how its arrays merge; an **overlay** changes part of it. The
 * shape deliberately mirrors `FragmentSource`, because it is the same problem one level up: several
 * contributors, one result, and a client with the last word.
 */
class UiBlockSource(
    /** The block this contributes to. A menu is a block; a menu item is not. */
    val blockId: String,
    /** Whether this changes a block rather than supplying it. */
    val isOverlay: Boolean,
    /** The client whose block this changes, or null for every client. */
    val client: String?,
    /** Where this came from, for diagnostics: a component's name, or a config's id. */
    val origin: String,
    /** This layer's JSON. */
    val content: Map<String, Any?>,
    /**
     * Which arrays merge by a primary key, as `path -> key field` (`"items" to "id"`), where the path is
     * dotted from the block's root.
     *
     * **Declared by the base only**, and that is enforced. An array with no rule is *replaced* wholesale by a
     * later layer rather than merged, because merging without a key means guessing that position identifies
     * an element -- which is true right up until somebody inserts one. Letting an overlay declare the rule
     * would let it change how it is itself merged, which is a question with no good answer.
     */
    val arrayKeys: Map<String, String> = emptyMap(),
) {
    init {
        if (isOverlay && arrayKeys.isNotEmpty()) {
            throw KdrException.mkConv(
                "The overlay of UiBlock '$blockId' from $origin declares merge rules. Only the base declares " +
                    "them: an overlay that could change how arrays merge would be changing how it is itself " +
                    "folded in.",
            )
        }
    }

    override fun toString(): String = "$blockId <- $origin" + (client?.let { " ($it)" } ?: "")
}

/** A block's content after every layer that applies has been folded in, with its arrays already ordered. */
class MergedUiBlock(
    val blockId: String,
    /** The client this was merged for, or null for what everybody else gets. */
    val client: String?,
    val content: Map<String, Any?>,
    /** The merge rules the base declared, carried so a consumer can find the key an array is identified by. */
    val arrayKeys: Map<String, String>,
    /** Whether any **base** layer supplied content; false when only overlays named this block. */
    val found: Boolean,
)

/** Declares a base UiBlock: its JSON, and which of its arrays merge by a primary key. */
fun uiBlock(
    blockId: String,
    origin: String,
    arrayKeys: Map<String, String> = emptyMap(),
    client: String? = null,
    build: UiBlockBuilder.() -> Unit,
): UiBlockSource = UiBlockSource(
    blockId, isOverlay = false, client = client, origin = origin,
    // Stamps `displayOrder` as items are written: a base is authoring a list, and the order it is written in
    // is the order it means.
    content = UiBlockBuilder(stampOrder = true).apply(build).build(), arrayKeys = arrayKeys,
)

/** Declares an overlay onto [blockId] -- naming only what it changes. */
fun uiBlockOverlay(
    blockId: String,
    origin: String,
    client: String? = null,
    build: UiBlockBuilder.() -> Unit,
): UiBlockSource = UiBlockSource(
    blockId, isOverlay = true, client = client, origin = origin,
    // **Never stamps `displayOrder`**, and that is the difference that matters. An overlay names only what it
    // changes, so an author adjusting a label must not silently renumber the item as well -- and an overlay
    // adding an item has to choose where it lands, which auto-numbering from 100 could not do meaningfully.
    content = UiBlockBuilder(stampOrder = false).apply(build).build(),
)

/**
 * Builds a UiBlock's JSON in code.
 *
 * A builder rather than a raw nested-map literal for the reason every other kd2 config has one: a literal has
 * nowhere to put a check, so a duplicate key silently wins. Here it is refused where it is written.
 */
class UiBlockBuilder(private val stampOrder: Boolean = false) {
    private val values = LinkedHashMap<String, Any?>()

    /** Sets [key]. Declaring one twice is refused rather than letting the second silently win. */
    fun set(key: String, value: Any?) {
        if (values.containsKey(key)) {
            throw KdrException.mkConv("The UiBlock key '$key' is set twice in one layer; one would win silently.")
        }
        values[key] = value
    }

    /** A nested object under [key]. */
    fun obj(key: String, build: UiBlockBuilder.() -> Unit) =
        set(key, UiBlockBuilder(stampOrder).apply(build).build())

    /**
     * An array under [key], each element built in turn.
     *
     * In a **base**, [startOrder] and [step] stamp [UIB.displayOrder] onto each element as it is added, so an
     * author writes items in the order they mean and the numbers follow. Spaced by [UIB.orderStep] by default,
     * which is what leaves room for a later contributor to land between two of them.
     *
     * In an **overlay** nothing is stamped: an item there names only what it changes, and an overlay adding an
     * item states its own [UIB.displayOrder] because only it knows where the item belongs.
     */
    fun items(
        key: String,
        startOrder: Int = UIB.orderStep,
        step: Int = UIB.orderStep,
        build: UiBlockItemsBuilder.() -> Unit,
    ) = set(key, UiBlockItemsBuilder(startOrder, step, stampOrder).apply(build).build())

    fun build(): Map<String, Any?> = values.toMap()
}

/** Collects an array's elements, stamping each with its [UIB.displayOrder]; see [UiBlockBuilder.items]. */
class UiBlockItemsBuilder(
    private val startOrder: Int,
    private val step: Int,
    private val stampOrder: Boolean,
) {
    private val elements = mutableListOf<Map<String, Any?>>()

    /** One element; in a base, ordered by where it was written unless it sets [UIB.displayOrder] itself. */
    fun item(build: UiBlockBuilder.() -> Unit) {
        val built = UiBlockBuilder(stampOrder).apply(build).build()
        elements.add(
            if (!stampOrder || built.containsKey(UIB.displayOrder)) built
            else built + (UIB.displayOrder to startOrder + step * elements.size),
        )
    }

    fun build(): List<Map<String, Any?>> = elements.toList()
}
