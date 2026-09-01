package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * A client's own cfacts, end to end (issue #455): declared in its config, present only in its registry, and
 * reported at a path naming it.
 *
 * The pair is the point, as it is for the schema variant beside this: acme sees a name globex does not, and
 * globex still sees every name acme does. That second half is the additive-only rule, and it is the one that
 * fails silently -- a client that could take a name away would break expressions in *shared* data for itself
 * alone, discovered by that client rather than by us.
 */
class ClientCFactTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("clientCFact", "clientCFactTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()))

    // The endpoint's answer depends on *which* caller asks. `acmeAdmin` and `globexAdmin` are each confined to
    // their own client -- the ordinary customer administrator, and who this surface is for -- while `crossClient`
    // holds the capability that reaches every client. `crossOperator` is a **deployment operator** (operator +
    // allClients, no admin): the caller `clientOperator` newly admits (issue #488), and the one the old
    // admin-shaped confinement check refused despite the capability -- so it reaches every client here.
    val acmeAdmin = TestUser.create(cxt, "cfact-admin@acme.test", userClient = SC.acme, level = ROLE.admin)
    val globexAdmin = TestUser.create(cxt, "cfact-admin@globex.test", userClient = SC.globex, level = ROLE.admin)
    val acmeOperator = TestUser.create(cxt, "cfact-op@acme.test", userClient = SC.acme, level = ROLE.operator)
    val crossClient = TestUser.create(
        cxt, "cfact-cross@example.com", level = ROLE.admin, capabilities = listOf(ROLE.allClients),
    )
    val crossOperator = TestUser.create(
        cxt, "cfact-crossop@example.com", level = ROLE.operator, capabilities = listOf(ROLE.allClients),
    )

    // The reference is one Markdown document (issue #488), so a cfact "appears" when its name is in the text --
    // it is rendered as an inline-code span, `acmeUnderAudit`, which a substring match finds.
    fun refAt(user: TestUser, path: String): String =
        user.getItem(path)[CFD.markdown].toOptStr().orEmpty()

    "a client's own cfact is in its registry and nobody else's" {
        val acme = SchemaService.get(cxt).cfactsFor(SC.acme).names
        acme shouldContain SC.underAudit
        SchemaService.get(cxt).cfactsFor(SC.globex).names shouldNotContain SC.underAudit
        SchemaService.get(cxt).cfactsFor(null).names shouldNotContain SC.underAudit
    }

    "a client adds without taking anything away" {
        // What keeps a component-authored expression valid at every client by construction, rather than by a
        // check somebody has to remember to run.
        val global = SchemaService.get(cxt).cfactsFor(null).names
        val acme = SchemaService.get(cxt).cfactsFor(SC.acme).names
        acme.containsAll(global) shouldBe true
        acme shouldBe global + SC.underAudit
    }

    "the shared reference answers with the caller's own client" {
        // Not with the deployment's set, and that is the useful answer rather than the tidy one: what an acme
        // author may write *is* acme's vocabulary, so serving them the global list would have them conclude
        // their own cfact does not exist. The same thing the shared gedra surface does with `cxt.client`.
        refAt(acmeAdmin, CFD.cfactsPath) shouldContain SC.underAudit
        // Somebody who belongs to no client of their own sees the deployment's set.
        refAt(crossClient, CFD.cfactsPath) shouldNotContain SC.underAudit
        refAt(crossClient, CFD.cfactsPath) shouldContain CFACTS.loggedIn
    }

    "a path naming the client answers with that client's" {
        // The copy `clientShaped` asks for -- the same shape as the gedra endpoints, and the reason the flag
        // exists outside that section at all. It is what lets an administrator who may reach every client ask
        // about one, and what puts acme's copy in the catalog acme's own people are shown.
        val ownPath = clientPath(CFD.cfactsPath, SC.acme)
        ownPath shouldBe "/clientOperator/${SC.acme}/cfacts"
        refAt(crossClient, ownPath) shouldContain SC.underAudit
        refAt(acmeAdmin, ownPath) shouldContain SC.underAudit
        // The capability, not the level, is what reaches a named client: a deployment *operator* holds it and
        // must pass, which the admin-shaped check (`adminScope == allClients`) refused (issue #488).
        refAt(crossOperator, ownPath) shouldContain SC.underAudit
        refAt(crossClient, clientPath(CFD.cfactsPath, SC.globex)) shouldNotContain SC.underAudit
    }

    "one client's operator or administrator cannot read another's reference" {
        // The confinement the section change made **necessary** rather than optional. Under `operator` this
        // was reachable only by people running the deployment, for whom every client is already theirs;
        // `clientOperator` admits a customer's own operator or admin, and a customer able to read their
        // neighbors' vocabulary is the same leak that decided cfact names are not held unique across clients.
        // Refused for a confined operator as well as a confined admin -- neither holds `allClients`.
        globexAdmin.expectError(EXC.badInput, clientPath(CFD.cfactsPath, SC.acme))
        acmeAdmin.expectError(EXC.badInput, clientPath(CFD.cfactsPath, SC.globex))
        acmeOperator.expectError(EXC.badInput, clientPath(CFD.cfactsPath, SC.globex))
    }

    "an expression may name a client's cfact only in that client's scope" {
        // The whole purpose of a per-client vocabulary: acme may author data naming `acmeUnderAudit`, and the
        // same string in shared data would refuse to parse rather than quietly meaning nothing.
        val acme = SchemaService.get(cxt).cfactsFor(SC.acme)
        acme.parse("${BOOT.app},${SC.underAudit}").render() shouldBe "${BOOT.app},${SC.underAudit}"
        runCatching { SchemaService.get(cxt).cfactsFor(null).parse(SC.underAudit) }.isFailure shouldBe true
    }
})
