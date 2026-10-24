package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage for [routeWrite] — whether a page's state change adds a history entry, rewrites the
 * current one, or leaves history alone (issue #324). Two maps in, one verdict out: no browser, no history.
 *
 * The bug it closes is not a wrong URL — the URL was always right. It is that every write was a rewrite, so a
 * whole page occupied one history entry and Back left it altogether, landing wherever the last genuinely
 * pushed entry was. What is under test is therefore the *distinction*: which changes are navigations and which
 * merely refine where you already are.
 *
 * Both pages that keep state in the address are covered, because the rule is only as good as the `identity` each
 * one declares — the catalog's endpoint, the users page's open record.
 */
class RouteWriteTest {

    // --- the endpoint catalog: which endpoint is open, refined by what has been typed into it ---------

    private val identity = setOf(HP.method, HP.path)

    private val list = mapOf(HP.page to HMENU.pageCatalog)

    private fun endpoint(method: String, path: String, values: String? = null): Map<String, String> =
        buildMap {
            put(HP.page, HMENU.pageCatalog)
            put(HP.method, method)
            put(HP.path, path)
            values?.let { put(HP.values, it) }
        }

    /** The address already says what we would write, so history is not touched — not even to rewrite it. */
    @Test
    fun writingWhatIsAlreadyThereDoesNothing() {
        assertEquals(RouteWrite.none, routeWrite(list, list, identity, true))
        val ep = endpoint("GET", "/demo/file/list")
        assertEquals(RouteWrite.none, routeWrite(ep, ep, identity, true))
    }

    /**
     * The case the issue reports. Opening an endpoint from the list is a move to somewhere else, so it earns
     * an entry — which is the entry Back then returns from.
     */
    @Test
    fun openingAnEndpointIsANavigation() {
        assertEquals(RouteWrite.push, routeWrite(list, endpoint("GET", "/demo/file/list"), identity, true))
    }

    /** And so is leaving one, whether for the list or for a different endpoint. */
    @Test
    fun leavingAnEndpointIsANavigationToo() {
        val ep = endpoint("GET", "/demo/file/list")
        assertEquals(RouteWrite.push, routeWrite(ep, list, identity, true))
        assertEquals(RouteWrite.push, routeWrite(ep, endpoint("POST", "/demo/file/upload"), identity, true))
        // Same path, different method is still a different endpoint -- the catalog identifies them by both.
        assertEquals(RouteWrite.push, routeWrite(ep, endpoint("POST", "/demo/file/list"), identity, true))
    }

    /**
     * Typing is not navigating. This is the whole reason the original code chose `replaceState` for
     * everything: the sync effect runs on the entered values too, so treating this as a navigation would mean
     * a history entry per keystroke.
     */
    @Test
    fun enteringValuesRewritesTheCurrentEntry() {
        val ep = endpoint("GET", "/demo/file/list")
        assertEquals(
            RouteWrite.replace,
            routeWrite(ep, endpoint("GET", "/demo/file/list", """{"a":1}"""), identity, true),
        )
        assertEquals(
            RouteWrite.replace,
            routeWrite(
                endpoint("GET", "/demo/file/list", """{"a":1}"""),
                endpoint("GET", "/demo/file/list", """{"a":12}"""),
                identity,
                true,
            ),
        )
        // Clearing them is the same kind of change: still the same endpoint.
        assertEquals(
            RouteWrite.replace,
            routeWrite(endpoint("GET", "/demo/file/list", """{"a":1}"""), ep, identity, true),
        )
    }

    /**
     * An address naming an endpoint the catalog does not have is a URL to correct, not a place to leave.
     *
     * This is the trap the rule falls into on its own: the page resolves an unknown endpoint to *nothing*
     * selected, so the identity differs and the plain rule would push — and landing back on that entry would
     * push again, leaving Back unable to get past it. Correcting it in place cannot loop.
     */
    @Test
    fun anAddressWeCannotHonorIsCorrectedInPlaceRatherThanLeft() {
        assertEquals(RouteWrite.replace, routeWrite(endpoint("GET", "/gone"), list, identity, false))
    }

    /**
     * Unreachable is about the address we are leaving, never the one we are writing — and it does not make us
     * rewrite an address that already agrees with us, since there is nothing to correct.
     */
    @Test
    fun anUnreachableAddressThatAlreadyAgreesIsStillLeftAlone() {
        val gone = endpoint("GET", "/gone")
        assertEquals(RouteWrite.none, routeWrite(gone, gone, identity, false))
    }

    /**
     * Params the page does not write (a debug flag, say) make the maps differ, so the address is rewritten —
     * which is what the previous code did unconditionally. It must not be read as a *navigation*, though:
     * losing a stray parameter is not somewhere Back should return to.
     */
    @Test
    fun anUnrelatedParamIsTidiedAwayWithoutEarningAnEntry() {
        val withExtra = endpoint("GET", "/demo/file/list") + ("fault" to "shell")
        assertEquals(RouteWrite.replace, routeWrite(withExtra, endpoint("GET", "/demo/file/list"), identity, true))
    }

    // --- the users page: which record the editor is open on ------------------------------------------

    private val userIdentity = setOf(HP.user)

    private val userList = mapOf(HP.page to HMENU.pageUsers)

    private fun editorOn(record: String): Map<String, String> =
        mapOf(HP.page to HMENU.pageUsers, HP.user to record)

    /**
     * The reported symptom: opening a user was React state and nothing else, so Back skipped the whole page.
     * Opening the editor is a navigation, and closing it returns to the list.
     */
    @Test
    fun openingAndClosingTheUserEditorAreNavigations() {
        assertEquals(RouteWrite.push, routeWrite(userList, editorOn("42"), userIdentity, true))
        assertEquals(RouteWrite.push, routeWrite(editorOn("42"), userList, userIdentity, true))
        assertEquals(RouteWrite.push, routeWrite(editorOn("42"), editorOn("43"), userIdentity, true))
    }

    /** Creating is its own destination, and not the same one as editing whoever was open before. */
    @Test
    fun creatingAUserIsItsOwnDestination() {
        assertEquals(RouteWrite.push, routeWrite(userList, editorOn(HP.newRecord), userIdentity, true))
        assertEquals(RouteWrite.push, routeWrite(editorOn("42"), editorOn(HP.newRecord), userIdentity, true))
    }

    /** Back onto an already-open record writes nothing at all, which is what leaves history traversable. */
    @Test
    fun returningToTheRecordTheAddressAlreadyNamesDoesNothing() {
        assertEquals(RouteWrite.none, routeWrite(editorOn("42"), editorOn("42"), userIdentity, true))
        assertEquals(RouteWrite.none, routeWrite(userList, userList, userIdentity, true))
    }

    /**
     * A `u=` naming a row this page does not hold — a link into a record outside the loaded search results —
     * closes the editor, and that correction must not read as a navigation either.
     */
    @Test
    fun aRecordWeCannotResolveIsCorrectedInPlace() {
        assertEquals(RouteWrite.replace, routeWrite(editorOn("999"), userList, userIdentity, false))
    }
}
