package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.HttpUtil
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The API guide (issue #1493), followed as a program with a key would follow it, so that it cannot fall behind the API
 * without a failing build.
 *
 * Each request the guide shows is marked on the line before its block (`<!-- kdr-api: patch -->`). This sends them in
 * order, with a full key made in a fresh client's sandbox on the reference deployment, as a person would hand one to a
 * program. Each must succeed, and the answer the guide shows after it, cut down as it is, must be the shape of the
 * real one: every field it shows is there, at the same place. The ids a later request names (`<form id>`,
 * `<user id>`) are taken from the answers that made them. The upload, shown as a `curl` command, is sent with the
 * parts that command names.
 *
 * The test instance assumes no env auth, so the key is shown what it is on a public site: the published API. Every
 * `/kda/...` endpoint the guide names is one it is shown, and the catalog names the guide, at the address the site
 * serves it from.
 */
class ApiGuideTest : StringSpec({
    val cxt = TestInstances.reference("apiGuide1493")
    val page = File(File("../..").canonicalFile, guideSourcePath).readText()
    val requests = guideRequests(page)

    // The program's key: made by a person in their client's sandbox, as the guide's "What you need" says.
    val founder = TestUser.create(cxt, "founder@apiguide1493.test")
    founder.postData(AEP.createOwnClient, mapOf(CLD.name to "Book Club"))[CLD.client].toOptStr().shouldNotBeNull()
    founder.postData(AEP.openSandbox, emptyMap())
    val secret = founder.postData(AKEY.selfCreate, mapOf(AKEY.name to "Guide", AKEY.access to AKEY.full, AKEY.days to 1))[AKEY.secret]
        .toOptStr().shouldNotBeNull()
    val program = TestHttpClient(cxt.instanceConfig).apply { setHeader(AKEY.authorizationHeader, "${AKEY.bearerScheme}$secret") }
    val keyed = TestUser(program, cxt, founder.getData(AEP.selfInfo))
    val sandbox = keyed.selfClient().shouldNotBeNull()

    "every request the guide shows succeeds, in order, and answers in the shape the guide shows" {
        requests.map { it.name } shouldContainAll listOf("selfInfo", "patch", "createForm", "saveAnswer", "createUser", "setLabels")
        val ids = linkedMapOf<String, String>()
        for (request in requests) {
            val body = ids.entries.fold(request.body) { text, (placeholder, id) -> text.replace(placeholder, id) }
            val handler = when (request.method) {
                HttpMethod.GET.name -> program.sendGetRequestRaw(request.path, HttpUtil.parseQuery(request.query))
                else -> program.sendEditRequest(
                    request.path.removePrefix(kdaRoot), null, body.jsonMap().shouldNotBeNull(), HttpMethod.valueOf(request.method),
                )
            }
            val answer = handler.rptResponseData?.jsonMap().orEmpty()
            withClue(request.name, answer) { handler.rptStatusCode shouldBe 200 }
            request.answer?.let { shown -> withClue(request.name, answer) { shapeMisses(shown, answer).shouldBeEmpty() } }
            when (request.name) {
                "createForm" -> ids["\"$formIdPlaceholder\""] = "\"${answer.at("results", "item", "gedraId")}\""
                "createUser" -> ids["\"$userIdPlaceholder\""] = answer.at("results", "userId")
            }
        }
        ids.keys shouldBe setOf("\"$formIdPlaceholder\"", "\"$userIdPlaceholder\"")
    }

    "the upload the guide shows, sent with the parts its command names, stores a file ref" {
        val command = uploadRegex.find(page).shouldNotBeNull()
        val parts = partRegex.findAll(command.value).associate { it.groupValues[1] to it.groupValues[2] }
        val url = urlRegex.find(command.value).shouldNotBeNull().groupValues[1]
        val (fileName, mimeType) = parts.getValue("file").removePrefix("@").split(";type=")
        val sent = parts + ("file" to ContentData(sampleSvg.toByteArray(), mimeType, saveAsFilename = fileName, inLine = false))
        val handler = program.sendUploadRequest(url.removePrefix(kdaRoot), sent)
        withClue("upload", handler.rptResponseData) { handler.rptStatusCode shouldBe 200 }
        handler.rptResponseData?.jsonMap().orEmpty().at("results", "refId") shouldBe parts.getValue("refId")
    }

    "every endpoint the guide names is one the key is shown, and the catalog names the guide where it is served" {
        val catalog = keyed.catalog()
        val shown = TestUser.endpointsIn(catalog).map { it[EI.path].toOptStr() }.toSet()
        // The catalog itself is always there to be asked, published or not, and a path ending in `/` names a family.
        val named = kdaPathRegex.findAll(page).map { it.groupValues[1] }
            .filterNot { it.endsWith("/") || it.startsWith(catalogPrefix) }.toSet()
        named.filterNot { it in shown || clientPath(it, sandbox) in shown }.shouldBeEmpty()

        val address = catalog[EI.guide].toOptStr().shouldNotBeNull()
        address shouldBe SchemaService.apiGuideAddress(cxt)
        address shouldBe "/st/api/doc/${HDOC.apiGuide}"
        program.sendGetRequestRaw(address).rptResponseData shouldBe page
        // The documents the guide names by their address on a site are served there too.
        siteDocRegex.findAll(page).map { it.groupValues[0] }.toSet().forEach { doc ->
            withClue(doc, null) { program.sendGetRequestRaw(doc).rptStatusCode shouldBe 200 }
        }
    }
})

private const val guideSourcePath = "docs/api-guide.md"
private const val kdaRoot = "/kda"
private const val catalogPrefix = "/schema/"
private const val formIdPlaceholder = "<form id>"
private const val userIdPlaceholder = "<user id>"
private const val sampleSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8"/></svg>"""

private val markerRegex = Regex("""^<!--\s*kdr-api:\s*(\S+)\s*-->$""")
private val kdaPathRegex = Regex("""`(?:GET |POST )?/kda(/[A-Za-z/]+)[`?]""")
private val siteDocRegex = Regex("""/st/api/doc/[a-zA-Z-]+""")
private val uploadRegex = Regex("""```bash\n(curl [^`]*?/upload)\n```""")
private val partRegex = Regex("""-F "?([A-Za-z]+)=([^"\s]+(?: [^"\s-][^"]*)?)"?""")
private val urlRegex = Regex("""https://[^/\s]+(/kda/\S+)""")

/** One request the guide shows: its marker's name, its request line, its body, and the answer shown after it, if any. */
private class GuideRequest(val name: String, val method: String, val path: String, val query: String?, val body: String, val answer: Map<String, Any?>?)

/**
 * The marked requests of [page], in order. A request block is a request line (`POST /kda/...`), optional headers, a
 * blank line, and a JSON body; the first JSON block after it, before the next request, is its answer as shown.
 */
private fun guideRequests(page: String): List<GuideRequest> {
    val lines = page.lines()
    val out = mutableListOf<GuideRequest>()
    for ((i, line) in lines.withIndex()) {
        val name = markerRegex.find(line.trim())?.groupValues?.get(1) ?: continue
        check(lines[i + 1].trim() == "```http") { "The guide's '$name' is not followed by an http block." }
        val block = lines.drop(i + 2).takeWhile { it.trim() != "```" }
        val (method, target) = block.first().trim().split(" ", limit = 2)
        val body = block.dropWhile { it.isNotBlank() }.joinToString("\n").trim()
        val after = lines.drop(i + 3 + block.size)
        val next = after.indexOfFirst { markerRegex.matches(it.trim()) || it.startsWith("#") }.let { if (it < 0) after.size else it }
        val answerAt = after.take(next).indexOfFirst { it.trim() == "```json" }
        val answer = if (answerAt < 0) null else {
            after.drop(answerAt + 1).takeWhile { it.trim() != "```" }.joinToString("\n").jsonMap()
                ?: error("The answer the guide shows for '$name' is not a JSON object.")
        }
        out += GuideRequest(name, method, target.substringBefore("?"), target.substringAfter("?", "").ifEmpty { null }, body, answer)
    }
    return out
}

/**
 * Where [actual] lacks what [shown] has: each field [shown] carries, at the same place, recursing into objects and into
 * a list's first element. Values are not compared: the guide's are an example's, the answer's this run's.
 */
private fun shapeMisses(shown: Any?, actual: Any?, at: String = ""): List<String> = when (shown) {
    is Map<*, *> -> {
        val real = actual as? Map<*, *> ?: return listOf("$at is not an object")
        shown.flatMap { (key, value) -> if (key !in real) listOf("$at.$key") else shapeMisses(value, real[key], "$at.$key") }
    }
    is List<*> -> {
        val real = actual as? List<*> ?: return listOf("$at is not a list")
        val first = shown.firstOrNull()
        if (first == null) emptyList() else if (real.isEmpty()) listOf("$at is empty") else shapeMisses(first, real.first(), "$at[0]")
    }
    else -> emptyList()
}

private fun Map<String, Any?>.at(vararg keys: String): String =
    keys.dropLast(1).fold(this) { map, key -> map[key].toJsonMapOrEmpty() }[keys.last()].toOptStr().shouldNotBeNull()

private fun <T> withClue(what: String, answer: Any?, block: () -> T): T =
    io.kotest.assertions.withClue("The guide's '$what', answered: $answer") { block() }
