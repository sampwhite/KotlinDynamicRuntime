package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Every trait declared in source code has a name of its own (issue #1520's rule, `traitNameOf`): a layout heading, or
 * a title on its data type. One with neither is named by its id humanized -- "Kdr:name" -- in the forms listing's
 * Contains column, a form's section heading, the workflow editor, and Design View. Booted with the widest set a test
 * reaches: the sample, and the reference flavor's demo clients.
 */
class SourceTraitNamesTest : StringSpec({

    "every trait declared in source code is named by a heading or a title, not by its id" {
        val cxt = Startup.mkTestBootCxt(
            "sourceTraitNames", "sourceTraitNames",
            mapOf(FLV.deployFlavorsEnvVar.name to "reference", "KDR_LOAD_SAMPLE" to "true"),
            additionalComponents = listOf(SampleComponent()),
        )
        val schema = SchemaService.get(cxt)
        // A sandbox's configurations are its parent's, copied: checked once, as the parent's.
        val declared = SchemaCollector.get(cxt)!!.gedraConfigs.configs
            .filter { it.origin == GedraConfigOrigin.source && !isSandboxClient(it.gedraId.client) }
        val unnamed = declared.flatMap { config ->
            val client = config.gedraId.client
            val store = schema.storeFor(client.takeUnless { it == GID.globalClient })
            config.traits.values.filter { trait ->
                // The trait's data type, found as a form's entries carry it: the union of a kind it applies to.
                val dataType = trait.appliesTo.firstNotNullOfOrNull { kind ->
                    store.types["${GCFG.globalNamespace}.${GU.unionName(kind)}"]?.variants?.byValue?.get(trait.traitId)
                        ?.properties?.get(GE.data)?.valueType
                }
                val heading = dataType?.name?.let { store.layouts[it] }?.label
                dataType?.title.isNullOrBlank() && heading.isNullOrBlank()
            }.map { "${config.gedraId.client}: ${it.traitId}" }
        }
        // Compared with the empty list, so a failure names every trait that has no name of its own.
        unnamed shouldBe emptyList()
    }
})
