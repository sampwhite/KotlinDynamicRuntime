package com.dynamicruntime.script

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

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

    // --- the wiring precondition (issue #257) ---------------------------------
    //
    // `injectComponent(...)` is defined by the injection prologue, so a call written without it is a
    // settings.gradle.kts that cannot compile -- and settings failures stop *every* Gradle command in the
    // workspace, for everyone using it, not just the feature being wired. These run against real temp files
    // because the bug was in what got written to disk, which a string-level test would not have caught.

    fun tempSettings(text: String): File {
        val dir = createTempDirectory("kdrSettingsTest").toFile()
        File(dir, "settings.gradle.kts").writeText(text)
        return File(dir, "settings.gradle.kts")
    }

    val prologue = "fun injectComponent(path: String, dir: String) {}\n"

    "ensureInjectComponent refuses to write a call the settings file cannot resolve" {
        val settings = tempSettings("rootProject.name = \"kd\"\ninclude(\"launch\")\n")

        ensureInjectComponent(settings, ":customConfig", "customConfig") shouldBe false
        // Nothing written: the file must stay as it was, not gain an unresolvable call.
        settings.readText().contains("injectComponent") shouldBe false
    }

    "ensureInjectComponent writes the call once the prologue is there" {
        val settings = tempSettings("rootProject.name = \"kd\"\n$prologue")

        ensureInjectComponent(settings, ":customConfig", "customConfig") shouldBe true
        settings.readText().contains("injectComponent(\":customConfig\", \"customConfig\")") shouldBe true

        // Idempotent, and it reports the call as present rather than adding a second one.
        ensureInjectComponent(settings, ":customConfig", "customConfig") shouldBe true
        Regex("^injectComponent", RegexOption.MULTILINE).findAll(settings.readText()).count() shouldBe 1
    }

    /**
     * The path that caused the bug. `readYes` treats EOF as "no", so a non-interactive run declines the
     * prompt -- and the old signature could not tell the caller that, which is exactly how the call came to be
     * written without its prologue.
     */
    "ensureInjectionPrologue reports false when it did not add the prologue" {
        val settings = tempSettings("rootProject.name = \"kd\"\n")
        val workDir = settings.parentFile
        val examples = File(createTempDirectory("kdrExamplesTest").toFile(), "examples").also { it.mkdirs() }
        File(examples, "settings.gradle.kts.example").writeText(
            "rootProject.name = \"kd\"\n// >>> injection\n$prologue// <<< injection\n",
        )

        // Stdin is not a terminal under the test runner, so readYes() sees EOF and declines.
        ensureInjectionPrologue(workDir, examples) shouldBe false
        settings.readText().contains("fun injectComponent(") shouldBe false
    }

    "ensureInjectionPrologue reports true when the prologue is already present, with no example to hand" {
        val settings = tempSettings("rootProject.name = \"kd\"\n$prologue")
        // A workspace that is already wired is wired whether or not this checkout can supply the block.
        ensureInjectionPrologue(settings.parentFile, File("/nonexistent-examples")) shouldBe true
    }
})
