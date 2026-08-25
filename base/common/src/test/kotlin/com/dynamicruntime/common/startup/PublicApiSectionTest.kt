package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.RequestService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Publication is restricted to the user sections, and refused at boot (issue #433).
 *
 * **What this protects is not access.** `publicApi` decides what the catalog advertises, so a stray mark
 * cannot expose anything -- the section gate still refuses an anonymous caller at an admin endpoint. What it
 * protects is the answerability of a promise: "what did we commit to supporting?" must be checkable by reading
 * one rule, and an override sitting anywhere turns that into a survey of every declaration.
 *
 * Refused at boot rather than documented for the same reason as the unruled-section check beside it: a rule
 * everyone has to remember is not a rule.
 */
class PublicApiSectionTest : StringSpec({

    fun endpoint(path: String, publicApi: Boolean) = KdrEndpoint(
        path = path, method = HttpMethod.GET, kind = EndpointKind.general, namespace = "t",
        description = "d", inputFields = null, inputTypeRef = null, includeLimit = false,
        outputSchema = emptyMap(), handler = { _, _ -> emptyMap<String, Any?>() }, publicApi = publicApi,
    )

    /** Boots a bare RequestService against a store holding exactly [eps], in [env]. */
    fun initWith(vararg eps: KdrEndpoint, env: String = ENV.unit) {
        val config = KdrInstanceConfig("publicApiCheck", env, ENV.liveSource, null)
        config.put(KdrSchemaStore.key, KdrSchemaStore(endpoints = eps.associateBy { it.collationKey }))
        RequestService().onCreate(KdrCxt("publicApiCheck", config))
    }

    "a published endpoint in a user section is accepted" {
        initWith(endpoint("/user/thing", publicApi = true))
        initWith(endpoint("/profile/thing", publicApi = true))
        initWith(endpoint("/gedra/thing", publicApi = true))
    }

    "an unpublished endpoint may sit anywhere" {
        initWith(endpoint("/admin/thing", publicApi = false), endpoint("/operator/thing", publicApi = false))
    }

    /**
     * The refusal. An admin endpoint that wants publishing gets a twin under a user section instead -- which
     * is also the honest thing to do, since a path documented to outside developers as `/admin/...` tells them
     * something false about what it is for.
     */
    "a published endpoint outside the user sections refuses the boot" {
        val e = shouldThrow<KdrException> { initWith(endpoint("/admin/thing", publicApi = true)) }
        e.fullMessage() shouldContain "/admin/thing"
        e.fullMessage() shouldContain "publicApi"
    }

    "the refusal names every offender, not just the first" {
        val e = shouldThrow<KdrException> {
            initWith(
                endpoint("/admin/one", publicApi = true),
                endpoint("/operator/two", publicApi = true),
                endpoint("/user/fine", publicApi = true),
            )
        }
        e.fullMessage() shouldContain "/admin/one"
        e.fullMessage() shouldContain "/operator/two"
        // The legitimate one is not reported as a problem.
        e.fullMessage().contains("/user/fine") shouldBe false
    }

    /**
     * Production warns instead of refusing -- the house rule for a defect a running deployment survives
     * (`MarkdownFragmentService.fragmentCheckMode`, `GedraConfigCollector`).
     *
     * It applies here precisely because this axis is not an access control: a node advertising an endpoint it
     * should not is answering every request correctly and mis-describing one of them. Taking a deployment
     * down to fix a documentation error would be the larger outage.
     */
    "production warns rather than refusing, since the deployment is still viable" {
        initWith(endpoint("/admin/thing", publicApi = true), env = ENV.prod)
    }

    /**
     * The distinction, asserted so it cannot quietly become "boot checks warn in prod". The unruled-section
     * check guards *access* -- a node that boots past it serves a section to anyone -- so it stays fatal
     * everywhere, production included.
     */
    "an unruled section still refuses the boot in production" {
        val e = shouldThrow<KdrException> {
            initWith(endpoint("/noSuchSection/thing", publicApi = false), env = ENV.prod)
        }
        e.fullMessage() shouldContain "access rules"
    }
})