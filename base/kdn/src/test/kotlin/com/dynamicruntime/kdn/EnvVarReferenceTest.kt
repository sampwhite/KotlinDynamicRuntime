package com.dynamicruntime.kdn

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.operator.OENV
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The operator environment-variable reference (issue #371): the genuinely-new half of the change, an
 * `operator`-section endpoint that assembles a Markdown document showing, per variable, the value it resolved
 * to **on this node** -- which no static file could. Rendered by the frontend the way the README is.
 */
class EnvVarReferenceTest : StringSpec({

    // Setting KDR_LOG_LEVEL as a config entry does double duty: it gives a variable a known value from a known
    // source (so the resolved half of the report has something concrete to assert), and referencing
    // `LogSetup.appLogLevelEnvVar` loads that declaring object -- so the variable is in the registry when the
    // document renders, independent of what else the suite happened to touch first.
    "an operator gets a Markdown reference showing this node's resolved values" {
        val cxt = Startup.mkTestBootCxt(
            "envRef", "envRefTest", mapOf(LogSetup.appLogLevelEnvVar.name to "warn"),
        )
        val operator = TestUser.create(cxt, "env-op@example.com", level = ROLE.operator)

        val md = operator.getData("/operator/env/reference")[OENV.markdown].toString()
        md shouldContain "# Environment variables"
        md shouldContain "## Logging"
        md shouldContain "KDR_LOG_LEVEL"
        // The resolved fact, not the documented default: the value, and that it came from the instance config.
        md shouldContain "`warn`"
        md shouldContain "instance config"
    }

    /** It names infrastructure detail (`KDR_DB_HOST`, `KDR_DB_USER`), so it sits behind the operator gate. */
    "an ordinary user cannot reach the environment-variable reference" {
        val cxt = Startup.mkTestBootCxt("envRefGate", "envRefGateTest")
        val plain = TestUser.create(cxt, "env-plain@example.com")
        plain.expectError(EXC.notAuthorized, "/operator/env/reference")
    }
})
