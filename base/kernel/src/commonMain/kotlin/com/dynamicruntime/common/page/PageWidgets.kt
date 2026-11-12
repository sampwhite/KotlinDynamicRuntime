package com.dynamicruntime.common.page

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs

/** Where a page is drawn (issue #1792): what a widget kind says it can be drawn on. */
@Suppress("EnumEntryName")
enum class PageSurface(val words: String, val description: String) {
    /** The app, for a signed-in or anonymous caller: the webapp's pages. */
    app("the app", "The app's pages, drawn for each caller, signed in or not."),

    /** A site generated from a client's data, drawn once rather than per caller. */
    site("a generated site", "A website generated from a client's data, drawn once rather than for each caller."),

    /** A mail's body. */
    mail("a mail", "A mail's body, which has no caller's page to draw a widget that reads or acts."),
}

/** Whose copy may place a widget kind (issue #1792). */
@Suppress("EnumEntryName")
enum class PageWidgetPlacement(val words: String) {
    /** Only copy a component ships, or a deployment's flavor: never a client's own copy. */
    shippedCopy("copy a component ships"),

    /** Any copy, a client's own included. */
    anyCopy("any copy, a client's included"),
}

/**
 * A kind of widget a page's fence may draw (issue #1792): its [name] (a fence's `widget`), what it draws
 * ([description]), whose copy may place it ([placement]), the [surfaces] it draws on, and its parameters ([params], the
 * Sch DSL over the kind's parameter object). What a surface does with a kind it cannot draw is to leave the fence out.
 */
class PageWidgetKind(
    val name: String,
    val description: String,
    val placement: PageWidgetPlacement,
    val surfaces: Set<PageSurface>,
    val params: SchTypeBuilder.() -> Unit = {},
) {
    /** The name of this kind's parameter type, under [PGW.namespace]. */
    val typeName: String get() = name.replaceFirstChar { it.uppercase() } + "Widget"
}

/**
 * The widget kinds a check or a renderer knows (issue #1792), with each kind's parameter type compiled once. The core
 * kinds are [PageWidgets.core]; a catalog is a list rather than a fixed set so a component can add kinds of its own.
 */
class PageWidgetCatalog(val kinds: List<PageWidgetKind>, cxt: KdrCxtBase = LiteCxt()) {
    private val byName: Map<String, PageWidgetKind> = kinds.associateBy { it.name }

    init {
        val twice = kinds.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (twice.isNotEmpty()) {
            val names = twice.sorted().joinToString(", ") { "'$it'" }
            throw KdrException.mkConv("Two page widget kinds are named $names; a kind's name is its own.")
        }
    }

    private val types: Map<String, SchType> = parseSchemaTypes(
        schemaDefs(cxt, PGW.namespace) {
            for (kind in kinds) {
                type(kind.typeName) {
                    type = SCT.kObject
                    description = "The parameters of a '${kind.name}' widget fence."
                    kind.params(this)
                }
            }
        },
    )

    /** The kind named [name], or null when there is none. */
    fun kind(name: String): PageWidgetKind? = byName[name]

    /** [kind]'s compiled parameter type. */
    fun paramsType(kind: PageWidgetKind): SchType = types.getValue("${PGW.namespace}.${kind.typeName}")
}

/**
 * The core widget kinds (issue #1792): the pieces of Home a page document places, registered with their parameters
 * before any page draws them (the page renderer is #1794). Kinds that read a caller's state in the app are the app's
 * alone; kinds that read a deployment's own state are for copy a component ships.
 */
object PageWidgets {
    val hero = PageWidgetKind(
        "hero", "The brand mark beside the wordmark (the home copy's brand). Nothing when the copy names no brand.",
        PageWidgetPlacement.anyCopy, setOf(PageSurface.app, PageSurface.site),
    )

    val jumpLinks = PageWidgetKind(
        "jumpLinks", "A row of links to the page's drawn fences that have a jumpLabel, and to the cards that ask for one.",
        PageWidgetPlacement.anyCopy, setOf(PageSurface.app, PageSurface.site),
    )

    val cardsBlock = PageWidgetKind(
        "cardsBlock",
        "A UiBlock of cards, resolved for the caller as the home cards are: every card the block gives them, or those " +
            "named.",
        PageWidgetPlacement.anyCopy, setOf(PageSurface.app),
    ) {
        property(PGW.block, "The UiBlock whose cards are drawn.", required = true)
        property(PGW.ids, "Only these cards, in this order: how a page places a card apart from the rest.") {
            type = SCT.array
            items { type = SCT.string }
        }
        property(PGW.except, "Every card but these: the cards placed elsewhere on the page.") {
            type = SCT.array
            items { type = SCT.string }
        }
    }

    val joinableDemos = PageWidgetKind(
        "joinableDemos",
        "The demo clients a visitor signed in to the public client may join, each with its roles, Open as, and Join. " +
            "Nothing where joining is not offered.",
        PageWidgetPlacement.shippedCopy, setOf(PageSurface.app),
    )

    val createOwnClient = PageWidgetKind(
        "createOwnClient",
        "The card that creates a person's own client, or opens the one they made. Only for a caller signed in to the " +
            "public client.",
        PageWidgetPlacement.shippedCopy, setOf(PageSurface.app),
    )

    val testAccess = PageWidgetKind(
        "testAccess", "Becoming another user, for a caller with test access on a test instance. Nothing for anyone else.",
        PageWidgetPlacement.shippedCopy, setOf(PageSurface.app),
    )

    val docLinks = PageWidgetKind(
        "docLinks", "Links to the documents the deployment lists.",
        PageWidgetPlacement.anyCopy, setOf(PageSurface.app),
    )

    /** Every core kind, in the order the reference lists them. */
    val coreKinds: List<PageWidgetKind> = listOf(hero, jumpLinks, cardsBlock, joinableDemos, createOwnClient, testAccess, docLinks)

    /** The catalog of the core kinds, compiled once. A benign race on first use compiles it twice. */
    private var compiled: PageWidgetCatalog? = null

    /** The catalog of the core kinds. */
    val core: PageWidgetCatalog get() = compiled ?: PageWidgetCatalog(coreKinds).also { compiled = it }
}
