package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.util.splitComma

/**
 * What a node *is*, for the purpose of deciding what it carries (issue #433): its boot role and its capability
 * tags.
 *
 * **Two axes because they are two shapes.** A role is singular -- exactly one thing must win when choosing the
 * environment-variable prefix, the default port and the launcher -- while capabilities are a set. Collapsing
 * them would force every declaration to enumerate combinations: an `allBackend` node would have to be named in
 * each gate beside `admin` and `consumer`, and every later split would double the lists. So a deployment that
 * runs both surfaces carries **both tags**, and no declaration ever mentions the combination.
 */
class NodeProfile(
    /** The boot role, normalized: a node that declares none is [BOOT.app]. */
    val role: String,
    /** Capability tags this node carries; empty on a node that declares none. */
    val tags: Set<String>,
) {
    override fun toString(): String = if (tags.isEmpty()) role else "$role ${tags.sorted()}"

    companion object {
        /**
         * Reads the profile off a booting instance's config.
         *
         * **This is the one place `bootRole ?: BOOT.app` happens**, which `BOOT.app` asked for: a running
         * node's role is null when it is an ordinary application (so environment-variable prefixing stays off
         * for every existing deployment), while a *declaration* needs a name because null cannot sit in a
         * list. Normalizing once here keeps that asymmetry from being restated at every comparison.
         */
        fun of(config: KdrInstanceConfig): NodeProfile {
            val configured = (config.get(ACFG.bootTags) as? Collection<*>)?.mapNotNull { it as? String }
            val declared = configured ?: config.getEnvVar(BOOT.bootTagsEnvVar)?.splitComma() ?: emptyList()
            return NodeProfile(config.bootRole ?: BOOT.app, declared.map { it.trim() }.filter { it.isNotEmpty() }.toSet())
        }
    }
}

/**
 * Where a contribution is present -- a component, a service, and later an endpoint or table (issue #433).
 *
 * **Empty means unconstrained on that axis**, so [anywhere] is the default and every existing declaration
 * keeps behaving as it did. A contribution that names roles is present only on those roles; one that names
 * tags is present only on a node carrying at least one of them; one that names both requires both.
 */
class Presence(
    /** Roles this is present on; empty for all roles. */
    val roles: Set<String> = emptySet(),
    /** Capability tags, any one of which admits it; empty for any tags. */
    val tags: Set<String> = emptySet(),
) {
    /**
     * Whether [node] carries this.
     *
     * Any-of within an axis and AND across them. All-of within an axis is deliberately not offered: nothing
     * needs it yet, and the shape is easy to add later and awkward to remove once declarations rely on it.
     */
    fun admits(node: NodeProfile): Boolean =
        (roles.isEmpty() || node.role in roles) && (tags.isEmpty() || tags.any { it in node.tags })

    /** True when this constrains nothing, so a caller can skip work rather than test every node. */
    val isUnconstrained: Boolean get() = roles.isEmpty() && tags.isEmpty()

    override fun toString(): String = when {
        isUnconstrained -> "anywhere"
        tags.isEmpty() -> "roles=${roles.sorted()}"
        roles.isEmpty() -> "tags=${tags.sorted()}"
        else -> "roles=${roles.sorted()} tags=${tags.sorted()}"
    }

    companion object {
        /** Present on every node -- the default, and what an undeclared contribution has. */
        val anywhere = Presence()
    }
}

/**
 * A service factory together with where it belongs (issue #433).
 *
 * **A wrapper rather than a property on the service**, and the registry is why: `bindAndInitServices` calls
 * the factory and only *then* reads `serviceName` off the instance. A profile carried as data on the service
 * could not be read without constructing a service that must not exist on this node -- harmless while
 * constructors are field initializers, and a lie about presence the day one does something. It is also the
 * only form that generalizes: an endpoint or a table has no instance to hold data.
 */
class ServiceEntry(
    val factory: () -> ServiceInitializer,
    val presence: Presence = Presence.anywhere,
)

/**
 * A service, optionally narrowed to some roles or capability tags.
 *
 * Narrowing is by **intersection with the owning component**, and it is enforced by checking both rather than
 * by combining them: the registry asks the component's presence and then the entry's, so an entry can only
 * ever subtract. Computing a combined value instead would need "constrains nothing" and "admits nothing" to be
 * different values, and an empty set cannot be both.
 */
fun service(
    factory: () -> ServiceInitializer,
    roles: Set<String> = emptySet(),
    tags: Set<String> = emptySet(),
): ServiceEntry = ServiceEntry(factory, Presence(roles, tags))
