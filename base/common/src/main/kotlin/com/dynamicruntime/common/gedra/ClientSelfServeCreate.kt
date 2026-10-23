package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.IDD
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.RandomUtil
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.home.HFRAG

/**
 * A person's own **self-serve** client (issue #1113): created from `public`, by anybody signed in there, one per
 * person -- the way into the product that needs nobody's say-so, beside the platform's create (#1054) and a
 * customer's (#1112), and through the platform's, so it is refused for what that refuses.
 *
 * - **One per person.** Counted per identity, not per user -- a `public` administrator makes more users of their
 *   own -- and recorded on the identity ([IDD.selfServeClient]) by a guarded write before the client is made, so
 *   two creates at once cannot both pass. The one stays used once made, whatever becomes of the client.
 * - **What the client is.** Its id is generated, `free_<name><random>`: the person names the client, the system
 *   names its id. It has no customer, so it is on the free plan ([isSelfServeClient]) and creates no clients of its
 *   own -- which is why the limit is enforced only here. Its audience is `customer`, its usage type follows the
 *   deployment's environment ([usageTypeIn]), it runs where the deployment does, and it has a sandbox, so it runs
 *   only what is published.
 * - **Who is in it.** The creator, as their own admin user there, under their identity; the endpoint moves the
 *   session there. Moving the client to a customer later is the platform's to do: it keeps its id, and the
 *   customer link is what makes it the customer's.
 */
@Suppress("ConstPropertyName")
object ClientSelfServeCreate {
    /** The longest part of the id taken from the client's name. */
    private const val maxSlug = 16

    /** The length of the id's random part: enough that two people naming their clients alike do not collide. */
    private const val randomLength = 6

    private val randomChars = ('a'..'z') + ('0'..'9')

    /** What the create did: the client's create, and the creator's own user in it. */
    class Result(
        val created: ClientCreate.Result,
        /** The creator's own admin user in the new client; null when making it failed ([userFailure]). */
        val userId: Long?,
        /** Why the creator's user could not be made, when it could not -- after the client was, so not to be repeated. */
        val userFailure: String? = null,
    )

    /**
     * Creates the self-serve client named [name] for the person signed in as [cxt]'s user, in [timeZone] -- the
     * creator's browser's, as the page sends it (issue #1115) -- or the deployment's when none is given.
     */
    fun create(
        cxt: KdrCxt,
        name: String,
        description: String?,
        timeZone: String? = null,
        /**
         * The new client's home copy (issue #1336): keys of the home file's `home` namespace ([CLD.homeCopyFields]'
         * values) to their text, trimmed, a blank one left out. Written and published with the client, so its home
         * page reads them at once, though the client has a sandbox.
         */
        home: Map<String, String> = emptyMap(),
        /** Whether the client starts with workflows made from the starter templates (issue #1346). */
        starterWorkflows: Boolean = true,
    ): Result {
        val profile = cxt.userProfile
        if (profile.client != CL.public) {
            throw KdrException.mkInput(
                "A self-serve client is created from '${CL.public}'; this session acts in '${profile.client}'.",
            )
        }
        val users = UserService.get(cxt)
        val identity = profile.identityId?.let { users.queryIdentityFresh(cxt, it) }
            ?: throw KdrException(
                "This session has no identity to give the new client's administrator; log in again.",
                code = EXC.authNeeded,
            )
        identity.identityData[IDD.selfServeClient].toOptStr()?.let {
            throw KdrException("You have created your client, '$it'; a person creates one.", code = EXC.conflict)
        }
        val client = newClientId(name)
        // The one is claimed before the client is made: a second create, at once, is refused here.
        identity.identityData[IDD.selfServeClient] = client
        try {
            users.updateIdentity(cxt, identity, guarded = true)
        } catch (e: KdrException) {
            if (e.code != EXC.conflict) throw e
            throw KdrException("Another create of your client is under way.", e, EXC.conflict)
        }
        val created = try {
            ClientCreate.create(cxt, definition(cxt, client, name, description, timeZone), copy = homeCopy(home), starterWorkflows = starterWorkflows)
        } catch (e: Exception) {
            // Nothing of the client was stored, so the one is not used.
            try {
                identity.identityData.remove(IDD.selfServeClient)
                users.updateIdentity(cxt, identity, guarded = true)
            } catch (undo: Exception) {
                LogGedra.error(cxt, "Client '$client' was refused, and its creator's claim on it could not be taken back.", undo)
            }
            throw e
        }
        // From here the client exists, whatever becomes of its creator's user: a failure is the result's to say.
        return try {
            val admin = PERSONA.def(PERSONA.admin) ?: throw KdrException("The admin persona is not declared.")
            Result(created, users.claimUser(cxt, identity, client, admin.name, "", admin.defaultRoles).userId)
        } catch (e: Exception) {
            LogGedra.error(cxt, "Client '$client' was created, but making its creator's admin user failed.", e)
            Result(created, null, e.message ?: "Making the creator's user failed.")
        }
    }

    /**
     * The usage type of a self-serve client made in the environment [env]: `production` in production, where people
     * rely on what they make, and `dev` everywhere else.
     */
    fun usageTypeIn(env: String): ClientUsageType = if (env == ENV.prod) ClientUsageType.production else ClientUsageType.dev

    /** The definition of the self-serve client [client], as the platform's create takes one. */
    private fun definition(cxt: KdrCxt, client: String, name: String, description: String?, timeZone: String?): Map<String, Any?> {
        val env = cxt.instanceConfig.env
        return buildMap {
            put(CLD.clientId, client)
            put(CLD.name, name)
            description?.let { put(CLD.description, it) }
            timeZone?.let { put(CLD.timeZone, it) }
            put(CLD.usageType, usageTypeIn(env).name)
            put(CLD.audience, ClientAudience.customer.name)
            // Where the deployment runs, and the developer's two, which a client running anywhere else must name.
            put(CLD.enabledEnvironments, ENV.names.filter { it == env || it == ENV.unit || it == ENV.local })
            put(CLD.sandbox, true)
        }
    }

    /**
     * The copy overlay for [home]: the home file's `home` namespace, with only the keys a self-serve create sets, each
     * trimmed, and none at all when every value is blank. Pure.
     */
    fun homeCopy(home: Map<String, String>): Map<String, Map<String, Map<String, String>>> {
        val allowed = CLD.homeCopyFields.values.toSet()
        val kept = home.filterKeys { it in allowed }.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        return if (kept.isEmpty()) emptyMap() else mapOf(HFRAG.home to mapOf(HFRAG.homeNs to kept))
    }

    /** A new self-serve client id for a client named [name]: `free_`, the name's letters and digits, and a random part. */
    private fun newClientId(name: String): String {
        val slug = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(maxSlug)
        val random = RandomUtil.random()
        val suffix = (1..randomLength).map { randomChars[random.nextInt(randomChars.size)] }.joinToString("")
        return "${CUST.free}${CUST.separator}$slug$suffix"
    }
}
