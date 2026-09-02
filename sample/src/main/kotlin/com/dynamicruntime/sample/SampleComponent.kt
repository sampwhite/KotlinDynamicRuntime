package com.dynamicruntime.sample

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.uiblock.uiBlock
import com.dynamicruntime.sample.gedra.SB
import com.dynamicruntime.sample.gedra.SF
import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.backendFragmentFiles
import com.dynamicruntime.common.content.fragmentFiles
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.content.fragmentOverlayFile
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.sample.file.SampleFileService
import com.dynamicruntime.sample.gedra.GedraFixtureEndpoints
import com.dynamicruntime.sample.gedra.sampleClients
import com.dynamicruntime.sample.gedra.sampleTraits
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceEntry
import com.dynamicruntime.common.startup.service

/**
 * The `sample` module's component. It contributes the file upload/download endpoints (owned by
 * [SampleFileService]) and registers the service that backs them.
 * Mirrors how each base module has one component (`CommonComponent`, `KdnComponent`).
 *
 * It once also carried a Todo CRUD demo. That went when the runtime stopped needing a toy to point at; the
 * file endpoints stayed because they are the only exercise of the `file` endpoint kinds -- multipart upload
 * and a raw response body -- which nothing else in the codebase covers.
 *
 * Discovered by the launcher via the ServiceLoader mechanism (issue #171) -- it is listed in
 * `src/main/resources/META-INF/services/com.dynamicruntime.common.startup.KdrProvider` -- rather than being
 * registered explicitly. Being a demo, it self-gates via [isLoaded]: its endpoints load only in developer
 * environments, so it never enters a real deployment's endpoint set.
 */
class SampleComponent : ComponentDefinition {
    override val providerName: String = "sample"

    /**
     * Loads the demo file endpoints only in developer environments (`local`/`dev`), never in
     * `prod`/`integration`; an explicit `KDR_LOAD_SAMPLE=true|false` overrides that (and lets tests force it on
     * regardless of environment). Formerly `shouldLoadSample` in the launcher's `Start.kt`.
     */
    override fun isLoaded(cxt: KdrCxt): Boolean {
        cxt.getEnvBool(loadSampleEnvVar)?.let { return it }
        val env = cxt.instanceConfig.env
        return env == ENV.local || env == ENV.dev
    }

    companion object {
        val loadSampleEnvVar = EnvVarDef(
            "KDR_LOAD_SAMPLE", group = ENVGRP.application, defaultDoc = "on for `local`/`dev`, off otherwise",
            description = "Force-loads (`true`) or skips (`false`) the `sample` module's demo file " +
                "upload/download endpoints, overriding the default (developer environments only).",
        )
    }

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(SampleFileService.schema(cxt))
        collector.addModule(GedraFixtureEndpoints.schema(cxt))
    }

    /**
     * The fragment layers the sample ships (issue #456), which between them exercise every kind there is: a
     * base file, a `_overlay.md` beside it, and an overlay written in code. Acme's own overlay of the same
     * file arrives through its Gedra config, so the file ends up with four contributors and one reader.
     */
    override fun fragments(cxt: KdrCxt): List<FragmentSource> =
        fragmentFiles(SF.content) +
            fragmentOverlayFile(SF.content) +
            // Acme's workflow copy (issue #533): a backend file, pulled by its creation workflow's labels.
            backendFragmentFiles(SF.acmeWf) +
            // In code rather than in a file, which is the case a small change should not need a resource for.
            // Applied after the overlay file above, so this is what a reader of `footer.copyright` gets.
            fragmentInline(SF.content, origin = "SampleComponent") {
                namespace(SF.footer) {
                    key(SF.copyright, "Copyright the sample deployment.")
                }
            }

    /**
     * The UiBlock the sample registers (issue #457), which exercises what a block can say: an item every
     * caller sees, one behind a caller cfact, one behind a boot-role cfact that an application never matches,
     * and one an overlay has retired with `#never`. Acme overlays it through its Gedra config.
     */
    override fun uiBlocks(cxt: KdrCxt): List<UiBlockSource> = listOf(
        uiBlock(SB.nav, origin = "SampleComponent", arrayKeys = mapOf(SB.items to SB.id)) {
            set(SB.title, "Sample")
            items(SB.items) {
                item { set(SB.id, SB.overview); set(SB.label, "Overview") }
                // Resolved per caller: present for an administrator and absent for everybody else, decided on
                // the side that knows rather than by the frontend reading a role.
                item { set(SB.id, SB.users); set(SB.label, "Users"); set(UIB.cfactExpression, CFACTS.hasAdminLevel) }
                // The boot role as a cfact: this application never matches it, and an edge would -- one set of
                // data serving both, with nothing removed for either.
                item { set(SB.id, SB.perimeter); set(SB.label, "Perimeter"); set(UIB.cfactExpression, BOOT.edge) }
                // Still in the list, and never shown. `#never` is how something is taken away without merging
                // learning to delete: the item is here to be read, which is what answers "why is this gone?".
                item { set(SB.id, SB.retired); set(SB.label, "Retired"); set(UIB.cfactExpression, CFACT.neverName) }
            }
        },
    )

    /**
     * Traits contributed for testing (issue #301). They join the manufactured `FormDocEntry` union alongside
     * the runtime's own, which is what gives the fixture a union with more than one branch to select between.
     */
    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(sampleTraits(cxt)) + sampleClients(cxt)

    override fun services(cxt: KdrCxt): List<ServiceEntry> =
        listOf(service(::SampleFileService))
}
