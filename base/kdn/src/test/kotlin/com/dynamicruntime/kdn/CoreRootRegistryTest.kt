package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.naming.OWNR
import com.dynamicruntime.common.naming.namespaceRoot
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Over a node booted with core's components alone: every global name core declares carries core's one root, `kdr`
 * (issue #950). The boot already refuses a component namespace off its owner's root; this pins that core's owner is
 * `kdr` for every one of them, so a namespace added later without it fails here rather than shipping.
 */
class CoreRootRegistryTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("coreRoots", "coreRootRegistryTest")

    "no core type or endpoint namespace lacks the kdr root" {
        val store = SchemaService.get(cxt).schemaStore
        store.types.keys.filter { namespaceRoot(it) != OWNR.kdrRoot }.shouldBeEmpty()
        store.endpoints.values.map { it.namespace }.distinct().filter { namespaceRoot(it) != OWNR.kdrRoot }.shouldBeEmpty()
    }

    // Issue #951: core's trait ids carry the same root -- data, state and config traits alike.
    "no global trait id in core lacks the kdr root" {
        val configs = SchemaCollector.get(cxt).shouldNotBeNull().gedraConfigs
        val ids = configs.traitsOwnedBy(GID.globalClient).map { it.traitId } +
            configs.stateTraits().map { it.traitId } + configs.configTraits().map { it.traitId }
        ids.filterNot { it.startsWith("${OWNR.kdrRoot}${OWNR.rootSep}") }.shouldBeEmpty()
    }
})
