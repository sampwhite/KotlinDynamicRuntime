package com.dynamicruntime.common.content

import com.dynamicruntime.common.util.MDR
import com.dynamicruntime.common.util.markdownRoles
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The roles are declared once (issue #1562), and both of the places that act on them agree with the list: the
 * stylesheet has a rule for every role, so a declared role never renders as nothing, and the boot's copy check reports a
 * role the list does not have. (The copy the platform ships is held to the list by every test that boots: the check is
 * strict outside production.)
 */
class MarkdownRoleStylesTest : StringSpec({

    "app.css styles every declared role" {
        val css = File("../../webapp/src/jsMain/resources/app.css").readText()
        val unstyled = markdownRoles.map { it.name }
            .filterNot { Regex("""\.${Regex.escape(MDR.classPrefix + it)}\b""").containsMatchIn(css) }
        unstyled shouldBe emptyList()
    }

    "shared copy writing an undeclared role is a fragment finding" {
        val base = FragmentSource("roleCheck", false, null, "test") {
            mapOf("ns" to mapOf("good" to "[x]{.value}", "bad" to "[x]{.vaule}"))
        }
        val findings = sharedCopyRoleProblems(listOf(base))
        findings.size shouldBe 1
        ("'roleCheck' ns.bad:" in findings[0]) shouldBe true
        // A client's own layer is judged with its configuration, not here.
        val mine = FragmentSource("roleCheck", true, "acme", "acme") { mapOf("ns" to mapOf("bad" to "[x]{.vaule}")) }
        sharedCopyRoleProblems(listOf(mine)) shouldBe emptyList()
    }
})
