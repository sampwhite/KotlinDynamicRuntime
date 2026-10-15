package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserService

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
    class Result(val created: ClientCreate.Result, val userId: Long)

    /**
     * Creates the client [request] -- its creator's choices -- describes, under the customer whose root is [cxt]'s
     * client.
     */
    fun create(cxt: KdrCxt, request: Map<String, Any?>): Result {
        val clients = ClientService.get(cxt)
        val root = cxt.client
        val customer = clients.customerOf(root)?.takeIf { it.rootClientId == root }
            ?: throw KdrException(
                "Only an administrator of a customer's root client creates its clients; '$root' is no customer's root.",
                code = EXC.notAuthorized,
            )
        val plan = customer.plan ?: PLAN.unlimited
        if (plan == PLAN.free) {
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
        val created = ClientCreate.create(cxt, ClientFieldPolicies.delegatedDefinition(parent.toInfo(), request))
        val admin = PERSONA.def(PERSONA.admin) ?: throw KdrException("The admin persona is not declared.")
        val user = UserService.get(cxt).claimUser(cxt, identity, created.client, admin.name, "", admin.defaultRoles)
        return Result(created, user.userId)
    }
}
