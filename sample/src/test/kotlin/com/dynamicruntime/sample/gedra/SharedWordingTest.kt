package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignPulledCopy
import com.dynamicruntime.common.gedra.DesignRefusal
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.traitDataTypeName
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Design View's shared wording (issue #1010): field copy pulled from a fragment file is named by the definition read
 * -- its key, the words the client reads and the shipped ones -- whatever the definition's origin, and is edited at the
 * key through the copy override path, so the change reaches everywhere the key is used. The sample's questionnaire is
 * a global trait whose topic description is `%{@t("questionnaire.topicHelp")}` from the `formHelp` file: acme reads
 * it as altered by its own configuration, and globex -- which has no sandbox, so an edit is live at once -- is where it
 * is edited.
 */
class SharedWordingTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "sharedWording1010", "sharedWording1010", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.createFullAdmin(cxt, "wording-admin@example.com")
    val dataType = "${ST.namespace}.${traitDataTypeName(ST.questionnaireEntry)}"
    val fragments = MarkdownFragmentService.get(cxt)

    fun topicDescription(client: String): Map<String, Any?> =
        admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to ST.questionnaire, DSV.client to client))[DSV.pulledCopy]
            .toJsonMapOrEmpty()[dataType].toJsonMapOrEmpty()[ST.topic].toJsonMapOrEmpty()[SL.description].toJsonMapOrEmpty()
    fun pull(client: String) = topicDescription(client)[DSV.pulls].toJsonListOfMaps().single()
    fun served(client: String) = fragments.effectiveFragmentsFor(cxt, SF.formHelp, client)?.content?.get("questionnaire")?.get("topicHelp")
    val address = mapOf(COV.fileId to SF.formHelp, COV.namespaceField to "questionnaire", COV.key to "topicHelp")

    "the definition read names the key a slot pulls, for a definition declared globally" {
        val described = topicDescription(SC.acme)
        described[DSV.mixed] shouldBe false
        val key = pull(SC.acme)
        key[COV.fileId] shouldBe SF.formHelp
        key[COV.namespaceField] shouldBe "questionnaire"
        key[COV.key] shouldBe "topicHelp"
        key[COV.value] shouldBe served(SC.acme)
        key[COV.baseValue] shouldNotBe null
        // Nothing of acme's own sets it: the shipped wording.
        key.containsKey(COV.origin) shouldBe false
        // A slot that is plain text names nothing.
        admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to ST.questionnaire, DSV.client to SC.acme))[DSV.pulledCopy]
            .toJsonMapOrEmpty()[dataType].toJsonMapOrEmpty()[ST.topic].toJsonMapOrEmpty().containsKey(SL.label) shouldBe false
    }

    "an edit at the key is the client's own wording wherever it is used, and reset returns the shipped one" {
        val shipped = served(SC.globex)
        admin.postData(CPY.setPath, address + mapOf(COV.client to SC.globex, COV.value to "What the questionnaire covers, in a line."))
        served(SC.globex) shouldBe "What the questionnaire covers, in a line."
        pull(SC.globex)[COV.value] shouldBe "What the questionnaire covers, in a line."
        pull(SC.globex)[COV.origin] shouldBe GedraConfigOrigin.stored.name
        pull(SC.globex)[COV.baseValue] shouldBe shipped
        // acme reads its own wording, untouched.
        served(SC.acme) shouldBe shipped

        admin.postData(CPY.resetPath, address + mapOf(COV.client to SC.globex))
        served(SC.globex) shouldBe shipped
        pull(SC.globex).containsKey(COV.origin) shouldBe false
    }

    "a client with a sandbox changes its wording from the sandbox, as every Design View save is made" {
        fun read(client: String) = admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to ST.questionnaire, DSV.client to client))
        read(SC.acme)[DSV.sharedWordingRefusalCode] shouldBe DesignRefusal.publishedOnly.name
        read(SC.globex).containsKey(DSV.sharedWordingRefusalCode) shouldBe false
    }

    "a pull is told from a pull mixed with other text, and a two-part key is read against the layout's file" {
        DesignPulledCopy.pulledKeys($$"""%{@t("help.topic")} for ${topic}""", "help") shouldBe listOf(Triple("help", "help", "topic"))
        DesignPulledCopy.isPullAlone("""%{@t("questionnaire.topicHelp")}""") shouldBe true
        DesignPulledCopy.isPullAlone($$"""%{@t("help.topic")} for ${topic}""") shouldBe false
        DesignPulledCopy.pulledKeys("""%{@t("other.ns.key")}""", "help") shouldBe listOf(Triple("other", "ns", "key"))
        // A two-part key with no file to read it against names nothing; plain text pulls nothing.
        DesignPulledCopy.pulledKeys("""%{@t("ns.key")}""", null) shouldBe emptyList()
        DesignPulledCopy.pulledKeys("Plain words", "help") shouldBe emptyList()
    }
})
