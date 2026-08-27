package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.content.FragmentMapBuilder
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.uiblock.UiBlockBuilder
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.uiblock.uiBlockOverlay
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.schema.qualifyTypeName

/**
 * One trait: a `traitId`, the entry type it generates, and the gedra kinds that may carry it (issue #298).
 *
 * The trait is the *definition*; the entry type is what the definition produces, and an entry stored on a
 * gedra is an instance of that type. Keeping the three straight is most of what you need to understand this layer.
 *
 * [traitId] is globally unique — across namespaces and across kinds — which is what lets stored data carry a
 * bare trait id and nothing else. [typeName] is namespaced, because two configs may each want a `NameEntry`.
 * The two rules look contradictory and are not: the namespace scopes the *types* traits create, never the
 * traits themselves.
 */
class GedraTrait(
    /** Globally unique id, and the discriminator value in a stored entry. */
    val traitId: String,
    /** Fully qualified name of the entry type this trait generated. */
    val typeName: String,
    /** The gedra kinds that may carry an entry of this trait; never empty. */
    val appliesTo: Set<GedraDataType>,
    /**
     * The schema of the trait's own `data` — the shape under [GE.data] in an entry of this trait (issue #337).
     *
     * Held here because a trait *is* its definition, and its data shape is the largest part of that. It was
     * previously reachable only by finding [typeName] in the built `$defs` and reading a property off it,
     * which made every consumer depend on the layout of a document rather than on the trait.
     *
     * It is what a second manufactured type is built from: the entry union declares complete entries, and the
     * patch's edit union declares the same data beside an action, so both are generated from *this* rather
     * than one from the other. Either a `$ref` to a named type or an inline object, depending on how the trait
     * was authored — the two are equivalent to a reader and neither is normalized here.
     */
    val dataSchema: Map<String, Any?>,
) {
    override fun toString(): String = "$traitId -> $typeName"
}

/**
 * A bundle of definitions — traits now, workflows later — carrying its own identity (issue #298), and
 * optionally the definition of the client it is filed under (issue #343).
 *
 * Bundling is the direction #292 settled on rather than an implementation convenience. Traits and the
 * workflows that use them are defined *together*, so `$ref`s and workflow-to-trait links stay mostly inside
 * one object instead of spreading across many. Links between bundles remain possible and are meant to be
 * few, which is what would make an audit of cross-links worth having later.
 *
 * ### Identity
 *
 * A config carries a [GedraId] whose base id is its own **code-explicit name**: `gc.cd.global.coreTraits`.
 * Deterministic rather than minted, which is the "non-random id targets a specific config entity" case from
 * #287 — and it is what lets a config defined in code and one loaded from a database sit side by side
 * without either needing to know which the other is. The revision suffix (`~3`) arrives with config storage;
 * an absent suffix will mean the active revision.
 *
 * ### The client segment is the activation scope
 *
 * The client in that id is not only ownership. It is how a deployment will decide **which configs, or which
 * pieces of them, are active for a given client** — the mechanism behind a client seeing its own view of the
 * schema and endpoints rather than everybody's. Nothing reads it that way yet, and the path is close enough
 * that the affordance is worth naming rather than rediscovering.
 *
 * The consequence that matters today: a config is identified by its **full id**, never by [name] alone. Two
 * clients may each declare a `coreTraits`, and they are different configs. Anything that indexes configs by
 * name is correct only for as long as `global` is the only client — a condition nothing enforces and nobody
 * will remember.
 *
 * The same segment bounds **visibility**: a config owned by a client may reference definitions owned by that
 * client or by `global`, and no others. That is why two clients defining one trait id is harmless — neither
 * can see the other's — and it is the half of the rule that has to be enforced when a second owner appears,
 * since `$ref` resolution over one compiled map would otherwise reach anywhere.
 */
class GedraConfig(
    /** This config's identity; its base id is [name]. */
    val gedraId: GedraId,
    /** The namespace the config's generated types live in. */
    val namespace: String,
    /** Its traits, keyed by [GedraTrait.traitId]. */
    val traits: Map<String, GedraTrait>,
    /**
     * The `$defs` this config contributes, keyed by qualified type name.
     *
     * Both what its traits generated **and** any type it declared directly — `GedraConfigBuilder` extends
     * `SchTypesBuilder`, so a config can define ordinary types beside its traits. That is how a client-scoped
     * config carries schema of its own, augmented or wholly new, without a second place to declare it.
     */
    val defs: Map<String, Any?>,
    /**
     * The client this config defines, when it defines one (issue #343).
     *
     * A typed field beside [traits] rather than a row somewhere, because a client *is* a definition. At most
     * one: the config's [gedraId] already carries a client, so a bundle declaring two would have to file both
     * under one id. Null is the ordinary case -- most bundles add traits to a client somebody else declared.
     */
    val client: ClientDef? = null,
    /**
     * The cfacts this config declares (issue #455) -- names its own data may then write in an expression.
     *
     * **Declaration only, never production.** A component is what makes a cfact true, because that takes
     * Kotlin; a config is data, and data has no place to put a computation. What a client declares here is
     * that a name *exists*, so an expression naming it parses -- which is exactly what a client needs before
     * it can author the data that uses it, and what lets the whole registry stay static per client.
     *
     * A client may only **add**. Redefining or removing a name a component declared is refused when the
     * registry is built, and the reason is the failure it prevents: an expression in shared, component-owned
     * data that parses everywhere except at one customer -- discovered by that customer. Additive-only keeps
     * every shared expression valid under every client's registry by construction rather than by a check
     * somebody has to remember to run.
     */
    val cfacts: List<CFactDef> = emptyList(),
    /**
     * The fragment overlays contributed by this config (issue #456) -- the copy its client reads in place
     * of the default.
     *
     * **Unlike a cfact, a client really can supply this**, and the difference is what each one *is*. A cfact
     * declaration needs Kotlin to decide it, which data has nowhere to put; a fragment overlay is a handful of
     * strings, which is exactly what configuration is made of. So this is the first thing a client's config
     * changes about what its people actually see.
     *
     * Always overlays, never a base: the base is the file the owning component ships, and a client replacing
     * it wholesale would drift out of step with every key that file later gains -- silently, since a missing
     * fragment renders its key path rather than failing.
     */
    val fragments: List<FragmentSource> = emptyList(),
    /**
     * The UiBlock overlays contributed by this config (issue #457) -- what its client's interface shows
     * where the deployment's own would show something else.
     *
     * Overlays only, as fragments are, and for the same reason: the base is the block its owning component
     * registered, and a client replacing one wholesale would drift out of step with every item that block
     * later gains -- silently, since an unmatched item is simply absent rather than an error.
     */
    val uiBlocks: List<UiBlockSource> = emptyList(),
) {
    /**
     * The code-explicit name this config is addressed by, which is also its id's base.
     *
     * Unique within a client, **not** across them — see the note on the client segment above. Index configs
     * by [gedraId] rather than by this.
     */
    val name: String get() = gedraId.baseId

    override fun toString(): String = gedraId.fullId
}

/**
 * Builds a [GedraConfig]. Extends [SchTypesBuilder], so a config can declare ordinary types beside its
 * traits — which is how a trait references a shared data shape instead of inlining one.
 */
class GedraConfigBuilder(
    cxt: KdrCxtBase,
    namespace: String,
    /** This config's name and client, so a contribution can say where it came from; see [fragmentOverlay]. */
    private val configName: String = "",
    private val configClient: String? = null,
) : SchTypesBuilder(cxt, namespace) {
    @Suppress("MemberVisibilityCanBePrivate")
    val traits: MutableMap<String, GedraTrait> = LinkedHashMap()

    /** The fragment overlays declared in this block; see [fragmentOverlay]. */
    @Suppress("MemberVisibilityCanBePrivate")
    val fragments: MutableList<FragmentSource> = mutableListOf()

    /**
     * Overlays some of [fileId]'s copy for this config's client (issue #456) -- see [GedraConfig.fragments].
     *
     * Name only the keys being changed. An overlay listing everything is one that stops matching the base the
     * first time the base gains a key, and nothing fails when it does: the frontend simply receives the
     * default wording for whatever the overlay never mentioned.
     *
     * The layer is stamped with this config's client and id, so a fragment report can say *which* config set a
     * value rather than only that something did.
     */
    fun fragmentOverlay(fileId: String, build: FragmentMapBuilder.() -> Unit) {
        fragments.add(fragmentInline(fileId, origin = configOrigin(), client = configClient, build = build))
    }

    /** How a contribution from this config identifies itself in a report. */
    private fun configOrigin(): String = if (configName.isEmpty()) "a Gedra config" else "config '$configName'"

    /** The UiBlock overlays declared in this block; see [uiBlockOverlay]. */
    @Suppress("MemberVisibilityCanBePrivate")
    val uiBlocks: MutableList<UiBlockSource> = mutableListOf()

    /**
     * Overlays part of [blockId] for this config's client (issue #457) -- see [GedraConfig.uiBlocks].
     *
     * Name only what is being changed. Items are matched by the primary key the base declared, so an item here
     * carrying that key changes the base's item rather than adding a second one, and an item carrying a key
     * the base does not have is a new item -- which must state its own `displayOrder`, since only this config
     * knows where it belongs.
     */
    fun uiBlockOverlay(blockId: String, build: UiBlockBuilder.() -> Unit) {
        uiBlocks.add(uiBlockOverlay(blockId, origin = configOrigin(), client = configClient, build = build))
    }

    /** The client this config defines, if it declared one; see [defineClient]. */
    var clientDef: ClientDef? = null
        private set

    /** The cfacts declared in this block; see [cfact]. */
    @Suppress("MemberVisibilityCanBePrivate")
    val cfacts: MutableList<CFactDef> = mutableListOf()

    /**
     * Declares a cfact this config's client may write in an expression (issue #455) -- see
     * [GedraConfig.cfacts] for why a config declares a name without producing it.
     *
     * [description] says what makes the cfact **true**, not what it means: whoever writes the expression is
     * trying to predict when it fires.
     *
     * Collisions are settled where the registry is built rather than here, because that is the first moment
     * holding every contributor: a name is unique across components *and* every config of one client, and no
     * single config can see enough to say so.
     */
    fun cfact(name: String, group: String, description: String) {
        cfacts.add(CFactDef(name, group, description))
    }

    /**
     * Declares the client this config defines (issue #343).
     *
     * Takes a built [ClientDef] rather than repeating its thirteen attributes as parameters. The two would
     * have to be kept in step by hand, and a definition loaded from data arrives as an object anyway -- so
     * this is the same call the data path will make.
     *
     * Named `defineClient` rather than `client` because `gedraConfig`'s own `client` parameter -- the owner
     * this config is filed under -- is in scope inside the block, and two different meanings of one word,
     * one a string and one a call, is a needless thing to have to resolve while reading.
     *
     * Declaring a second one throws immediately rather than being collected as a config-set problem: two
     * clients in one bundle is an authoring mistake with no coherent reading, where the checks in
     * `GedraConfigCollector` are about how separately-authored bundles fit together.
     */
    fun defineClient(def: ClientDef) {
        clientDef?.let {
            throw KdrException.mkConv(
                "This config already defines the client '${it.clientId}' and cannot also define " +
                    "'${def.clientId}'. A config's id carries exactly one client, so a second definition " +
                    "would have nowhere to be filed.",
            )
        }
        clientDef = def
    }

    /**
     * Declares a trait whose data shape is written here — the everyday form.
     *
     * [typeName] is given rather than derived from [traitId] on purpose. The two serve different readers: a
     * trait id is a key that appears in stored data forever, while a type name appears in `$defs`, in `$ref`s
     * and in exports. Deriving one from the other would tie a schema artifact to a storage key and make
     * renaming either a migration of the other.
     */
    fun trait(
        typeName: String,
        traitId: String,
        appliesTo: Set<GedraDataType>,
        description: String? = null,
        dataSchema: SchTypeBuilder.() -> Unit,
    ) {
        // Checked before anything is built, so a duplicate is refused rather than half-declared, and recorded
        // afterward, because the trait cannot be described until its data schema exists.
        val qualified = qualifyTypeName(typeName, namespace)
        checkTraitIsNew(qualified, traitId)
        val built = traitEntry(typeName, traitId, appliesTo, description, dataSchema)
        traits[traitId] = GedraTrait(traitId, qualified, appliesTo, built)
    }

    /**
     * Declares a trait whose data shape is a type declared elsewhere — the same thing as passing
     * `{ ref(dataType) }`, spelled so that the two authoring styles are equally visible to somebody reading
     * the DSL rather than its documentation.
     */
    fun trait(
        typeName: String,
        traitId: String,
        appliesTo: Set<GedraDataType>,
        dataType: String,
        description: String? = null,
    ) {
        trait(typeName, traitId, appliesTo, description) { ref(dataType) }
    }

    /** Refuses a trait id or a generated type name this config has already used. */
    private fun checkTraitIsNew(qualified: String, traitId: String) {
        traits[traitId]?.let {
            throw KdrException.mkConv(
                "Trait '$traitId' is declared twice in one config, as '${it.typeName}' and as '$qualified'. " +
                    "A trait id identifies one definition; two of them are either one trait declared twice " +
                    "or two concepts sharing a name.",
            )
        }
        traits.values.firstOrNull { it.typeName == qualified }?.let {
            throw KdrException.mkConv(
                "Traits '${it.traitId}' and '$traitId' both generate the type '$qualified'. The second " +
                    "would silently replace the first, so it is refused here instead.",
            )
        }
    }
}

/**
 * Declares a config bundle and its traits.
 *
 * ```kotlin
 * gedraConfig(cxt, "coreTraits", "globalconfig") {
 *     trait("NameEntry", "name", setOf(GedraDataType.formDoc)) {
 *         property("name", "What to call it.", required = true) { maxLength = 128 }
 *     }
 * }
 * ```
 *
 * [client] is the owning client, and defaults to the reserved [GID.globalClient] because a config declared in
 * code belongs to the deployment rather than to any one client. It is a parameter rather than a constant
 * because the class is the same either way — a config loaded from a client's own storage differs only in
 * this segment of its id.
 */
fun gedraConfig(
    cxt: KdrCxtBase,
    name: String,
    namespace: String,
    client: String = GID.globalClient,
    build: GedraConfigBuilder.() -> Unit,
): GedraConfig {
    // A config is addressed by name in code and in scripts, so the name has to be a legal identifier. The
    // base-id rules alone do not say that -- they admit a leading digit, which is fine for a minted id and
    // not for something somebody types as a variable.
    if (name.isEmpty() || !(name[0].isLetter() || name[0] == '_')) {
        throw KdrException.mkConv(
            "Config name '$name' has to start with a letter or an underscore: a config is addressed by this " +
                "name from code, so it has to be usable as a variable name.",
        )
    }
    // The name and client are handed to the builder rather than stamped onto what it produced: a contribution
    // that knows where it came from can be built complete, and nothing downstream has to rewrite it.
    val configClient = client.takeIf { it != GID.globalClient }
    val builder = GedraConfigBuilder(cxt, namespace, name, configClient).apply(build)
    return GedraConfig(
        // `of` validates the name as it builds the id, so a config called something a base id cannot spell is
        // refused here rather than at whatever later point first tried to address it.
        gedraId = GedraId.of(GedraConfigType.configDoc, client, name),
        namespace = namespace,
        traits = builder.traits.toMap(),
        defs = builder.defs.toMap(),
        client = builder.clientDef,
        cfacts = builder.cfacts.toList(),
        fragments = builder.fragments.toList(),
        uiBlocks = builder.uiBlocks.toList(),
    )
}
