package com.dynamicruntime.script

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers the pure helpers in `SettingsSupport.kt` that `kdr-install` and `kdr-create-config` share for reading
 * and editing `settings.gradle.kts` — prologue detection/insertion, the marked-block lift, and the
 * `injectComponent` presence check (a commented-out construct counts as present).
 */
class SettingsSupportTest : StringSpec({

    "hasInjectionPrologue detects the injectComponent helper" {
        hasInjectionPrologue("rootProject.name = \"x\"\nfun injectComponent(path: String, dir: String) {}") shouldBe true
        hasInjectionPrologue("rootProject.name = \"x\"\ninclude(\"launch\")") shouldBe false
    }

    "extractMarkedBlock returns the marked span inclusive, or null when absent" {
        val text = "a\n// >>> demo — hi\nline1\nline2\n// <<< demo\nb"
        extractMarkedBlock(text, "demo") shouldBe "// >>> demo — hi\nline1\nline2\n// <<< demo"
        extractMarkedBlock("no markers here", "demo") shouldBe null
    }

    "insertInjectionPrologue inserts after rootProject.name, or returns null without it" {
        val settings = "pluginManagement {}\nrootProject.name = \"x\"\ninclude(\"launch\")\n"
        val out = insertInjectionPrologue(settings, "PROLOGUE")!!
        out shouldBe "pluginManagement {}\nrootProject.name = \"x\"\n\nPROLOGUE\ninclude(\"launch\")\n"
        insertInjectionPrologue("include(\"launch\")\n", "PROLOGUE") shouldBe null
    }

    "hasInjectComponent counts an active OR commented-out call, and ignores other paths" {
        hasInjectComponent("injectComponent(\":customConfig\", \"customConfig\")", ":customConfig") shouldBe true
        hasInjectComponent("  // injectComponent(\":customConfig\", \"customConfig\")", ":customConfig") shouldBe true
        hasInjectComponent("injectComponent(\":acmeBilling\", \"../x\")", ":customConfig") shouldBe false
        hasInjectComponent("rootProject.name = \"x\"", ":customConfig") shouldBe false
    }

    "removeMarkedBlock deletes the span (and a blank line above), or leaves text unchanged when absent" {
        removeMarkedBlock("a\n\n# >>> demo\nx\n# <<< demo\nb", "demo") shouldBe "a\nb"
        removeMarkedBlock("just text", "demo") shouldBe "just text"
    }

    "spliceIntoRc inserts before a 'keep me last' sentinel, else appends" {
        val withSentinel = "alias l='ls'\n\n# THIS MUST BE AT THE END for sdkman\nexport SDKMAN_DIR=x\n"
        spliceIntoRc(withSentinel, "# added\nexport PATH=y") shouldBe
            "alias l='ls'\n\n# added\nexport PATH=y\n\n# THIS MUST BE AT THE END for sdkman\nexport SDKMAN_DIR=x\n"
        spliceIntoRc("alias l='ls'\n", "# added\nexport PATH=y") shouldBe "alias l='ls'\n\n# added\nexport PATH=y\n"
        spliceIntoRc("", "X") shouldBe "X\n"
    }

    "isBelowTailSentinel is true only when the block sits under the sentinel" {
        isBelowTailSentinel("# MUST BE AT THE END\n# >>> demo\nx\n# <<< demo", "demo") shouldBe true
        isBelowTailSentinel("# >>> demo\nx\n# <<< demo\n# MUST BE AT THE END", "demo") shouldBe false
        isBelowTailSentinel("# >>> demo\nx\n# <<< demo", "demo") shouldBe false
    }
})
