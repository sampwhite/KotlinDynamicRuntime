package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toOptStr

/**
 * A client created by a customer's **root administrator** (issue #1112): the customer's own way to grow, beside the
 * platform's create (#1054), which it goes through.
 *
 * - **Who.** An administrator of the root client of a customer -- the caller's own client, which the customer names
 *   as its root ([CustomerDef.rootClientId]). Nobody else: the authority to make a customer's clients sits with the
 *   root, whose administrators act for the customer as a whole.
 * - **On which plan.** Not the free plan, which makes no clients. An unlimited customer's root makes as many as it
 *   likes until a plan says otherwise.
 * - **What the client starts as.** Empty: its definition holds the creator's choices
 *   ([ClientFieldPolicies.creatorFields] -- its id, name, note, template, and sandbox), what the root passes on (its
 *   customer, where it runs, what it is for, whose it is, its web resources), and nothing else ([ClientFieldPolicies.delegatedDefinition]). Its id is
 *   `<customer>_<name>`, held to the customer's prefix by the write as every new client is.
 * - **Who is in it.** The creator, as their own admin user there, under their identity -- returned, not switched to:
 *   the caller moves there with the client switch (`/user/self/switchClient`), so an API caller's session is not
 *   moved as a side effect.
 */
object ClientDelegatedCreate {
    /** What the create did: the client's create, and the creator's own user in it. */
    class Result(
        val created: ClientCreate.Result,
        /** The creator's own admin user in the new client; null when making it failed ([userFailure]). */
        val userId: Long?,
        /**
         * Why the creator's user could not be made, when it could not -- after the client was, so the create is not to
         * be repeated (it would be refused for a taken id): the user is made by the console once the cause is mended.
         * Said in the result rather than thrown, as the platform create says a load that failed.
         */
        val userFailure: String? = null,
    )

    /**
     * Creates the client [request] -- its creator's choices -- describes, under the customer whose root is [cxt]'s
     * client.
     */
    fun create(cxt: KdrCxt, request: Map<String, Any?>): Result {
        val clients = ClientService.get(cxt)
        val root = cxt.client
        // Making a customer's clients is customer-wide authority, so an administrator confined to an organization of
        // the root -- who sees only that organization -- does not have it.
        cxt.userProfile.org?.let { org ->
            throw KdrException(
                "An administrator confined to the organization '$org' does not create the customer's clients.",
                code = EXC.notAuthorized,
            )
        }
        val customer = clients.customerOf(root)?.takeIf { it.rootClientId == root }
            ?: throw KdrException(
                "Only an administrator of a customer's root client creates its clients; '$root' is no customer's root.",
                code = EXC.notAuthorized,
            )
        // Read as every plan decision reads it: a plan name that is no plan is the free plan, which makes no clients.
        if (PLAN.named(customer.plan) == PLAN.free) {
            throw KdrException.mkInput(
                "The customer '${customer.customerId}' is on the ${PLAN.free} plan, which makes no clients.",
            )
        }
        val identity = cxt.userProfile.identityId?.let { UserService.get(cxt).queryIdentityById(cxt, it) }
            ?: throw KdrException(
                "This session has no identity to give the new client's administrator; log in again.",
                code = EXC.authNeeded,
            )
        val parent = clients.known(root)
            ?: throw KdrException("Client '$root' has no definition on this node.", code = EXC.notFound)
        // The customer's prefix first (issue #1288), before anything looks the id up: the platform's create says
        // whether an id is taken, and where it is defined, before the write judges the prefix -- which, for an id
        // outside the customer, would tell its root's administrators which of other customers' clients exist. Inside
        // the prefix, a taken id is one of the customer's own, which they may see.
        val clientId = request[CLD.clientId].toOptStr().orEmpty()
        newClientPrefixFault(clients, clientId, customer.customerId)?.let { throw KdrException.mkInput(it) }
        val created = ClientCreate.create(
            cxt, ClientFieldPolicies.delegatedDefinition(parent.toInfo(), request),
            starterWorkflows = request[CLD.starterWorkflows] != false,
        )
        // From here the client exists, whatever becomes of its creator's user: a failure is the result's to say.
        return try {
            val admin = PERSONA.def(PERSONA.admin) ?: throw KdrException("The admin persona is not declared.")
            Result(created, UserService.get(cxt).claimUser(cxt, identity, created.client, admin.name, "", admin.defaultRoles).userId)
        } catch (e: Exception) {
            LogGedra.error(cxt, "Client '${created.client}' was created, but making its creator's admin user failed.", e)
            Result(created, null, e.message ?: "Making the creator's user failed.")
        }
    }
}
