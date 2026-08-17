package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt

/** The names the clients `base/common` defines are addressed by (issue #343). */
@Suppress("ConstPropertyName")
object CLC {
    /** The config bundle defining [CL.hub]: `gc.cd.hub.hubClient`. */
    const val hubConfig = "hubClient"

    /** The config bundle defining [CL.public]: `gc.cd.public.publicClient`. */
    const val publicConfig = "publicClient"

    /**
     * The namespace each of those bundles owns, `<clientId>config`, echoing `globalconfig`.
     *
     * Neither declares a type today. A namespace is claimed anyway, because namespace ownership is how one
     * client's definitions stay invisible to another, and a client that acquires its first type should not
     * have to also acquire a namespace at that moment -- the claim is the cheap half.
     */
    fun namespaceOf(clientId: String): String = "${clientId}config"
}

/**
 * The clients every deployment has (issue #343).
 *
 * Two, and the pair is the whole of the client model until the sample clients arrive: [CL.hub] is ours and
 * [CL.public] is everybody's. Declared by `base/common`'s component rather than by a sample, for the reason
 * `coreTraits` gives -- these are part of what the runtime *is*, and anything a test needs to reach has to
 * come from a component that always loads.
 *
 * Both include exactly [CLD.allGlobal] and vary nothing, so both compute a schema equal to the global one.
 * That is what makes `public` stop being an exception: it is not a rule the schema builder enforces that
 * `public` never creates a variant, it is the result of what `public` declares.
 */
fun coreClients(cxt: KdrCxt): List<GedraConfig> = listOf(hubClient(cxt), publicClient(cxt))

/**
 * The deployment's own client: internal activity, batch work, and whatever acts with nothing else said.
 *
 * `production` and `internal`, which together are why it may take a functional group. The restriction on
 * [CLD.allGlobal] is about two parties -- somebody else depending on a trait we ship and changed -- and here
 * there is no second party: we ship the trait, we run the client, we are the reviewer.
 *
 * Enabled everywhere. It is the acting default for a context that names no client, so an environment where it
 * was absent would be one where internal work had nowhere to happen.
 */
fun hubClient(cxt: KdrCxt): GedraConfig =
    gedraConfig(cxt, CLC.hubConfig, CLC.namespaceOf(CL.hub), client = CL.hub) {
        defineClient(
            ClientDef(
                clientId = CL.hub,
                name = "Hub",
                description = "The deployment's own client: staff, batch activity, and internal work.",
                usageType = ClientUsageType.production,
                audience = ClientAudience.internal,
                enabledEnvironments = ENV.names.toSet(),
                includedTraits = listOf(CLD.allGlobal),
            ),
        )
    }

/**
 * The client every self-registered user lands in, and the one the anonymous profile carries.
 *
 * `demo` rather than `production`, and the tension in that is settled rather than ignored. The worry was that
 * a demo type would put demo conveniences -- relaxed security, bulk delete of recent content -- within reach
 * of the client holding every registered user. Those conveniences are administrative, and **no normal user
 * holds admin privilege over `public`**: every user in it is effectively their own client, so
 * `ReadScope.ofUser` is the only width that ever applies and the reach does not exist.
 *
 * Its web resources are the application's default anonymous ones, which is what [ClientDef.webResourcesId]
 * being absent means; the packaging that would let it name a set of its own is later work.
 */
fun publicClient(cxt: KdrCxt): GedraConfig =
    gedraConfig(cxt, CLC.publicConfig, CLC.namespaceOf(CL.public), client = CL.public) {
        defineClient(
            ClientDef(
                clientId = CL.public,
                name = "Public",
                description = "Self-registered users, each effectively their own client.",
                usageType = ClientUsageType.demo,
                audience = ClientAudience.customer,
                enabledEnvironments = ENV.names.toSet(),
                includedTraits = listOf(CLD.allGlobal),
            ),
        )
    }
