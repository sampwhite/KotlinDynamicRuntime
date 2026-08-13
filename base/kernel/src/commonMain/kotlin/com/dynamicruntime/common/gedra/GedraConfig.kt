package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.schema.qualifyTypeName

/**
 * One trait: a `traitId`, the entry type it generates, and the gedra kinds that may carry it (issue #298).
 *
 * The trait is the *definition*; the entry type is what the definition produces, and an entry stored on a
 * gedra is an instance of that type. Keeping the three straight is most of understanding this layer.
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
) {
    override fun toString(): String = "$traitId -> $typeName"
}

/**
 * A bundle of definitions — traits now, workflows later — carrying its own identity (issue #298).
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
    /** The `$defs` contents its traits generated, keyed by qualified type name. */
    val defs: Map<String, Any?>,
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
class GedraConfigBuilder(cxt: KdrCxtBase, namespace: String) : SchTypesBuilder(cxt, namespace) {
    @Suppress("MemberVisibilityCanBePrivate")
    val traits: MutableMap<String, GedraTrait> = LinkedHashMap()

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
        record(typeName, traitId, appliesTo)
        traitEntry(typeName, traitId, appliesTo, description, dataSchema)
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

    private fun record(typeName: String, traitId: String, appliesTo: Set<GedraDataType>) {
        val qualified = qualifyTypeName(typeName, namespace)
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
        traits[traitId] = GedraTrait(traitId, qualified, appliesTo)
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
    val builder = GedraConfigBuilder(cxt, namespace).apply(build)
    return GedraConfig(
        // `of` validates the name as it builds the id, so a config called something a base id cannot spell is
        // refused here rather than at whatever later point first tried to address it.
        gedraId = GedraId.of(GedraConfigType.configDoc, client, name),
        namespace = namespace,
        traits = builder.traits.toMap(),
        defs = builder.defs.toMap(),
    )
}
