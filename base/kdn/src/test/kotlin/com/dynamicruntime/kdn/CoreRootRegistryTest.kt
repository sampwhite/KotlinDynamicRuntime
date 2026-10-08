package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.naming.OWNR
import com.dynamicruntime.common.naming.namespaceRoot
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Over a node booted with core's components alone: every global name core declares carries core's one root, `kdr`
 * -- its namespaces (issue #950), trait ids (#951), cfacts (#952), and workflow and task ids (#953). The boot already
 * refuses a component namespace off its owner's root; this pins that core's owner is `kdr` for every one of them, so
 * a name added later without it fails here rather than shipping.
 */
class CoreRootRegistryTest : StringSpec({
    val cxt = TestInstances.default("coreRoots")

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

    // Issue #952: so do its cfacts, and the root retired the `wf` prefix that once kept workflow facts apart.
    "no framework cfact lacks the kdr root, and none keeps the wf prefix" {
        val names = SchemaCollector.get(cxt).shouldNotBeNull().cfacts.keys
        names.shouldNotBeEmpty()
        names.filterNot { it.startsWith("${OWNR.kdrRoot}${OWNR.rootSep}") }.shouldBeEmpty()
        names.filter { it.substringAfter(OWNR.rootSep).startsWith("wf") }.shouldBeEmpty()
    }

    // Issue #953: and its workflows and their tasks. Vacuous today -- core ships no workflow -- and here for the first.
    "no global workflow or task id in core lacks the kdr root" {
        val workflows = WorkflowService.get(cxt).forClient(null).workflows.values.map { it.def }
        val ids = workflows.map { it.workflowId } + workflows.flatMap { wf -> wf.tasks.map { it.id } }
        ids.filterNot { it.startsWith("${OWNR.kdrRoot}${OWNR.rootSep}") }.shouldBeEmpty()
    }
})
