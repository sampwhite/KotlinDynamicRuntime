package com.dynamicruntime.script

import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

// The scenarios kdr-probe ships with. Both are checks that were assembled by hand while verifying issues #211
// and #212 -- and the first of them was assembled wrongly, twice, which is the argument for their being here
// rather than rebuilt from memory each time.

/** Name of the [catalogDiff] scenario. */
const val catalogDiffName = "catalog-diff"

/** Name of the [accessMatrix] scenario. */
const val accessMatrixName = "access-matrix"

/** Name of the [grantThenCall] scenario. */
const val grantThenCallName = "grant-then-call"

/** How many endpoints to ask the catalog for -- above any plausible registered count, so nothing is truncated. */
private const val catalogLimit = 500

/**
 * What each rung of the ladder is shown by `/schema/endpoints`, side by side (the catalog filter, issue #211).
 *
 * The value is in the *differences*: a rung that gains nothing over the one below it is either a filter bug or
 * a section nobody has claimed yet, and neither is visible from one caller's listing. When the instance offers
 * the `explainAccess` debug tag (a test instance, issue #215) each caller's withheld sections are printed with
 * the role they want, so a surprising count comes with its own explanation instead of an inference.
 */
fun catalogDiff(cxt: ProbeContext) {
    println("Catalog visibility at ${cxt.baseUrl}")
    println()

    var previous: Set<String> = emptySet()
    for (level in probeLevels) {
        val session = cxt.sessionAt(level)
        val response = session.sendGetRequest(
            "/schema/endpoints",
            mapOf(EP.limit to catalogLimit, EP.debug to SS.explainAccess),
        )
        if (!response.isSuccess) {
            println("  ${session.label.padEnd(9)}: HTTP ${response.statusCode} ${response.errorMessage ?: ""}")
            continue
        }
        val paths = response.results[EI.endpoints].toJsonListOfMaps().mapNotNull { it[EI.path].toOptStr() }.toSet()
        val gained = (paths - previous).sorted()
        println("  ${session.label.padEnd(9)}: ${paths.size} endpoints" + gainedSuffix(level, gained))
        for (section in withheldSections(response.meta)) {
            println("             withheld: $section")
        }
        previous = paths
    }
    println()
    println("A rung that gains nothing over the one below it is worth a look: either the filter is wrong, or")
    println("no section has claimed that level yet.")
}

/** The " (+N new: ...)" tail, omitted for the first (anonymous) row, which has nothing to be new against. */
private fun gainedSuffix(level: String?, gained: List<String>): String = when {
    level == null -> ""
    gained.isEmpty() -> "  (gains nothing)"
    else -> "  (+${gained.size}: ${gained.joinToString(", ")})"
}

/** The withheld sections an `explainAccess` `_meta` block reports, one readable line each; empty when absent. */
private fun withheldSections(meta: Map<String, Any?>): List<String> {
    val explained = meta[SS.accessExplained].toJsonMapOrEmpty()
    return explained[SS.withheld].toJsonListOfMaps().map { entry ->
        val paths = entry[EI.endpoints].toJsonListOfStrings()
        "${entry[SS.section].toOptStr()} needs ${entry[SS.requiredRole].toOptStr()} (${paths.size}): " +
            paths.joinToString(", ")
    }
}

/**
 * Every caller against every path, as a grid of status codes -- the table hand-built three times across #211
 * and the operator work, and the fastest way to see that a gate says what it should.
 *
 * Paths come from the arguments, or default to one endpoint per privilege level. Read it by rows: a rung
 * should be admitted everywhere the rung below it is, because the ladder ranks them (issue #212), so a `200`
 * above a `403` in the same column is the shape of a real bug.
 */
fun accessMatrix(cxt: ProbeContext) {
    val paths = cxt.args.ifEmpty {
        listOf("/health", "/profile/ui/config", "/operator/system/info", ADEP.users)
    }
    println("Access matrix at ${cxt.baseUrl}")
    println()

    val width = paths.maxOf { it.length }
    println("  ${"caller".padEnd(10)}${paths.joinToString("  ") { it.padEnd(width) }}")
    for (level in probeLevels) {
        val session = cxt.sessionAt(level)
        val cells = paths.map { path -> session.sendGetRequest(path).statusCode.toString().padEnd(width) }
        println("  ${session.label.padEnd(10)}${cells.joinToString("  ")}")
    }
    println()
    println("401 = not logged in, 403 = logged in without the rung, 200 = admitted.")
    println("A path where a higher rung is refused while a lower one is admitted contradicts the role ladder.")
}

/**
 * Grants a rung to a session that is already logged in and re-probes it without a new cookie.
 *
 * Two claims meet here, and one of them has already been broken. The dispatcher re-reads live roles before
 * enforcing, so a grant takes effect on the next request (issue #212) -- and the catalog must agree, which it
 * did not: it filtered on the roles the session cookie carried at login, so the endpoint stayed hidden from
 * exactly the person just given the role, for the cookie's whole life (issue #211). Refusal and listing are
 * different code paths reaching for the same answer, which is why watching them together is worth a standing
 * scenario rather than a reconstruction each time.
 */
fun grantThenCall(cxt: ProbeContext) {
    // Granting `admin` opens the scoped-admin (`userAdmin`) surface, which is what makes the point here.
    // `operator` is not the vehicle since #464 fenced the operator surface behind the `allClients` capability,
    // which no endpoint grants -- so a granted operator would stay refused and the scenario would show no
    // change at all.
    val scopedPath = UADEP.users
    println("Granting ${ROLE.admin} to a live session at ${cxt.baseUrl}")
    println()

    val admin = cxt.sessionAt(ROLE.admin)
    // A brand-new address every run, deliberately. `becomeUser` applies a level only when it *creates* the
    // user, so a fixed address against a long-lived instance would return the already-promoted user from the
    // previous run -- and the "before" half of this scenario would quietly stop meaning anything.
    val subject = cxt.session("subject")
    val email = "probe-grantee-${System.currentTimeMillis()}@example.com"
    val info = subject.becomeUser(email)
    val userId = info[UPF.userId].toOptLong()
        ?: throw KdrException("becomeUser did not return a ${UPF.userId} for '$email'.")
    println("  subject $email (userId $userId), roles ${info[UPF.roles].toJsonListOfStrings()}")

    println("  before: $scopedPath -> ${subject.sendGetRequest(scopedPath).statusCode}" +
        ", catalog lists it: ${catalogLists(subject, scopedPath)}")

    val granted = admin.sendPostRequest(
        ADEP.userSetRoles,
        mapOf(ADF.userId to userId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
    )
    if (!granted.isSuccess) {
        throw KdrException("Could not grant ${ROLE.admin} (HTTP ${granted.statusCode}): ${granted.errorMessage}")
    }
    println("  granted ${ROLE.admin} as ${admin.label}, no new cookie issued to the subject")

    println("  after:  $scopedPath -> ${subject.sendGetRequest(scopedPath).statusCode}" +
        ", catalog lists it: ${catalogLists(subject, scopedPath)}")
    println()
    println("Expected: before 403 / false, after 200 / true. A 200 with a false is the gate and the catalog")
    println("disagreeing -- the endpoint is callable but not advertised to the caller who may call it.")
}

/** Whether [path] appears in the catalog as [session] sees it right now. */
private fun catalogLists(session: ProbeSession, path: String): Boolean {
    val response = session.sendGetRequest("/schema/endpoints", mapOf(EP.limit to catalogLimit))
    return response.results[EI.endpoints].toJsonListOfMaps().any { it[EI.path].toOptStr() == path }
}
