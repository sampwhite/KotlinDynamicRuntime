package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.startup.SchemaCollector

/** The friendly labels the built-in cfacts group under. Metadata: they affect nothing but presentation. */
@Suppress("ConstPropertyName")
object CFGRP {
    /** Facts about the node serving the request. */
    const val node = "Node"

    /** Facts about who is calling. */
    const val caller = "Caller"
}

/**
 * The built-in cfacts' names (issue #455). The boot-role ones are absent on purpose: they **are**
 * [BOOT.app] and [BOOT.edge], and a second spelling of a role's name is a thing that can come to disagree
 * with the first.
 */
@Suppress("ConstPropertyName")
object CFACTS {
    const val loggedIn = "loggedIn"
    const val anonymous = "anonymous"
    const val isOperator = "isOperator"
    const val isAdmin = "isAdmin"

    /** True when the caller may reach the deployment-operator surface; see the declaration for why. */
    const val isDeploymentOperator = "isDeploymentOperator"
}

/**
 * Declares the cfacts every deployment has: what this node is, and who is calling (issue #455).
 *
 * **Contributed by a component every node carries, and that is load-bearing.** A cfact declared by an
 * optional component is absent from the registry on a node without it -- so shared data naming it would parse
 * on some nodes and refuse the boot on others, which is the same shape of failure the additive-only rule
 * keeps clients from causing. Anything a *shared* expression may name has to be declared by something
 * universal; a cfact only one component's own data refers to may be declared by that component.
 *
 * ### Why the boot role is a cfact
 *
 * It is what lets **one set of data serve every node**. An edge does not remove the application's menu items;
 * it fails to match them. Without this, an edge's UI would have to be described by subtracting from the
 * application's, and every item added later would need somebody to remember to subtract it again.
 *
 * ### Naming
 *
 * A boot-role cfact is the role's own name (`app`, `edge`), because there is exactly one boot role and
 * nothing else the bare word could mean. A caller-level cfact is `is`-prefixed, because the bare word would
 * be read as **holding that role** when what it means is the privilege ladder: [CFACTS.isOperator] is true
 * for an administrator who does not hold `operator` at all.
 */
fun addCoreCFacts(collector: SchemaCollector) {
    // The profile the boot already computed, not a fresh `NodeProfile.of` off the config. Two reasons, and
    // the second is the one that bites: a node's role is fixed for its life, so each of these is a constant
    // per node rather than something to recompute per request -- and the config is read *again* between when
    // `InstanceRegistry` fixes the profile and when this runs, since every component's `applyInstanceConfig`
    // sits in between. A component defaulting the boot role there would leave the presence gates admitting an
    // application while this said `edge`, so the one cfact whose job is to report what the node is would
    // report the opposite of what it loaded.
    val role = collector.node.role
    collector.addCFact(
        CFactDef(
            BOOT.app, CFGRP.node,
            "True on an ordinary application node -- the boot role a node has when it declares none. " +
                "Exactly one boot-role cfact is present on any node.",
        ),
    ) { role == BOOT.app }
    collector.addCFact(
        CFactDef(
            BOOT.edge, CFGRP.node,
            "True on an edge node: the perimeter that fronts other servers, booted by `StartEdge`.",
        ),
    ) { role == BOOT.edge }

    collector.addCFact(
        CFactDef(
            CFACTS.loggedIn, CFGRP.caller,
            "True when the request carries an authenticated identity -- neither the anonymous profile nor " +
                "the unauthenticated system one.",
        ),
    ) { it.userProfile.isLoggedIn }
    collector.addCFact(
        CFactDef(
            CFACTS.anonymous, CFGRP.caller,
            "True when the request carries no authenticated identity. The positive form of `~loggedIn`, and " +
                "preferred over it: an expression written by exclusion admits every state invented later.",
        ),
    ) { !it.userProfile.isLoggedIn }
    collector.addCFact(
        CFactDef(
            CFACTS.isOperator, CFGRP.caller,
            "True when the caller holds `operator`, or `admin` by the privilege ladder. A statement about " +
                "**level only**: since issue #464 the `operator` section also requires `allClients`, so this " +
                "is no longer the same test that section applies -- use `${CFACTS.isDeploymentOperator}` for that.",
        ),
    ) { RoleLadder.satisfies(it.userProfile.roles, ROLE.operator) }
    collector.addCFact(
        CFactDef(
            CFACTS.isDeploymentOperator, CFGRP.caller,
            "True when the caller may reach the `operator` section -- the deployment-wide surface, which " +
                "takes the operator level **and** the `allClients` capability since issue #464. A " +
                "client-scoped administrator is not one, despite the ladder ranking them above operator.",
        ),
        // Asked of the dispatcher rather than restated here, which is the whole point: the menu offering this
        // surface and the gate admitting a caller to it are then one answer, and cannot drift (the #211
        // invariant). Restating "operator and allClients" would be a second copy of a rule that has already
        // changed once.
    ) { RequestService.get(it).sectionAdmits(it.userProfile, SECT.operator) }
    collector.addCFact(
        CFactDef(
            CFACTS.isAdmin, CFGRP.caller,
            "True when the caller holds `admin`. It says what the caller may do, not over whose rows: the " +
                "`allClients` capability is a separate axis and is no cfact yet.",
        ),
    ) { RoleLadder.satisfies(it.userProfile.roles, ROLE.admin) }
}
