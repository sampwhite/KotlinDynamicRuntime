package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ACCT
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFEAT
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AccountHistory
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The signed-in user's own history on the profile config (issue #1480): when the user was registered and activated, its
 * last sign-in, and when its person's address was proven -- the caller's own row and identity, and nothing of the
 * person's other users. The instance validates every response against its schema, so each case also pins that the
 * history is declared as it is sent.
 *
 * An invited user nobody has claimed cannot sign in to read it (signing in is the claim), so the invited case reads
 * it at the claim.
 */
class ProfileAccountTest : StringSpec({
    val cxt = TestInstances.default("profileAccount1480")

    /** [user]'s profile config: its features and its state. */
    fun config(user: TestUser): Pair<Map<String, Any?>, Map<String, Any?>> {
        val data = user.getData(AEP.profileUiConfig)
        return data[UIC.features].toJsonMapOrEmpty() to data[UIC.state].toJsonMapOrEmpty()
    }

    /** The history in a profile config's [state], read as the page reads it. */
    fun historyOf(state: Map<String, Any?>): AccountHistory = AccountHistory.fromInfo(state[AFLD.account].toJsonMapOrEmpty())

    "a registered user's history is their row's dates and their address's proof" {
        val user = TestUser.register(cxt, "acct-registered@example.com", "acctRegistered")
        val (features, state) = config(user)
        val history = historyOf(state)
        val row = UserService.get(cxt).queryByUserId(cxt, user.userId).shouldNotBeNull()
        history.registeredAt.shouldNotBeNull() shouldBe row.registeredAt
        history.activatedAt.shouldNotBeNull() shouldBe row.activatedAt
        history.lastLoggedInAt.shouldNotBeNull() shouldBe row.lastLoggedInAt
        // A registration proves the address with its code.
        history.verifiedAt.shouldNotBeNull()
        // No password was set on the way: the page says so from its features, beside the history.
        features[AFEAT.hasPassword] shouldBe false
    }

    "an invited user is activated when invited, registered when claimed, and their address is not proven by the claim" {
        val email = "acct-invited@example.com"
        // As an administrator's invitation provisions: a user nobody has claimed, at an address nobody has proven.
        val userId = UserService.get(cxt).provisionUser(cxt, email, CL.public, listOf(ROLE.user), createdAt = cxt.now())
        val before = UserService.get(cxt).queryByUserId(cxt, userId).shouldNotBeNull()
        before.registeredAt.shouldBeNull()
        // As stored, to the millisecond.
        val invitedAt = before.activatedAt.shouldNotBeNull()

        // Signing in is the claim; the fixture's is proof by fiat of the user, not of the address.
        val user = TestUser.create(cxt, email)
        user.userId shouldBe userId
        val history = historyOf(config(user).second)
        history.activatedAt shouldBe invitedAt
        history.registeredAt.shouldNotBeNull()
        history.lastLoggedInAt.shouldNotBeNull()
        history.verifiedAt.shouldBeNull()
        // An unproven address is absent from the wire rather than sent as null.
        config(user).second[AFLD.account].toJsonMapOrEmpty().containsKey(ACCT.verifiedAt) shouldBe false
    }

    "a sandbox user's history is its own, not the parent user's it was opened from" {
        val parent = "acctsbx"
        val setup = cxt.mkSubContext("setup", parent).also { it.userId = 14800L }
        GedraConfigService.get(cxt).writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(parent), parent) {
                defineClient(
                    ClientDef(
                        clientId = parent, name = "Client $parent", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                    ),
                )
            },
        )
        GedraConfigReload.reloadClient(cxt, parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val parentHistory = historyOf(config(admin).second)

        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        val (_, state) = config(admin)
        state[AFLD.userInfo].toJsonMapOrEmpty()[UPF.client] shouldBe sandboxOf(parent)
        val history = historyOf(state)
        val row = UserService.get(cxt).queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        history.activatedAt.shouldNotBeNull() shouldBe row.activatedAt
        history.lastLoggedInAt shouldBe row.lastLoggedInAt
        // The same person, so the same proof of the same address.
        history.verifiedAt shouldBe parentHistory.verifiedAt
    }
})
