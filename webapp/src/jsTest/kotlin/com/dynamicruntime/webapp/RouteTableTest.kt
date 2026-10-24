package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.home.HMENU
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The route table (issue #1125): each page's params written as a path, and the path read back as the same params --
 * what lets [routeParams] answer for a path what it answered for a hash, so no page had to change. Under Node there
 * is no app root, so an address here is what follows `/wa` in the browser.
 */
class RouteTableTest {
    /** The params [href] reads back as: [routeParamsOf] over its path, query and fragment. */
    private fun readBack(href: String): Map<String, String> {
        val fragment = href.substringAfter('#', "")
        val beforeFragment = href.substringBefore('#')
        return routeParamsOf(beforeFragment.substringBefore('?'), beforeFragment.substringAfter('?', ""), fragment)
    }

    private fun assertRoute(path: String, vararg params: Pair<String, String>) {
        assertEquals(path, routeHref(params.toList()), "written")
        assertEquals(params.toMap(), readBack(path), "read back")
    }

    @Test
    fun everyPageHasItsPathAndReadsBackAsItsParams() {
        assertRoute("/")
        assertRoute("/catalog", HP.page to HMENU.pageCatalog)
        assertRoute("/docs/code-guide", HP.page to HMENU.pageDocs, HP.doc to "code-guide")
        assertRoute("/login", HP.page to HMENU.pageLogin)
        assertRoute("/register", HP.page to HMENU.pageRegister)
        assertRoute("/claim", HP.page to HMENU.pageClaim)
        assertRoute("/profile", HP.page to HMENU.pageProfile)
        assertRoute("/users", HP.page to HMENU.pageUsers)
        assertRoute("/users/42", HP.page to HMENU.pageUsers, HP.user to "42")
        assertRoute("/users/new", HP.page to HMENU.pageUsers, HP.user to HP.newRecord)
        assertRoute("/clients/acme", HP.page to HMENU.pageClients, HP.client to "acme")
        assertRoute("/customers/acme", HP.page to HMENU.pageCustomers, HP.customer to "acme")
        assertRoute("/reports/audits", HP.page to HMENU.pageReports, HP.report to "audits")
        assertRoute("/forms", HP.page to HMENU.pageForms)
        assertRoute("/forms/new", HP.page to HMENU.pageNewForm)
        assertRoute("/forms/new-for-user", HP.page to pageCreateForUser)
        assertRoute("/forms/gd.fd.acme.a", HP.page to pageSurveyEdit, HP.gedra to "gd.fd.acme.a")
        assertRoute("/forms/gd.fd.acme.a/edit", HP.page to pageSurveyEdit, HP.gedra to "gd.fd.acme.a", HP.edit to "1")
        assertRoute("/forms/gd.fd.acme.a/raw", HP.page to pageEditForm, HP.gedra to "gd.fd.acme.a")
        assertRoute("/workflows", HP.page to HMENU.pageWorkflows)
        assertRoute("/workflows/review/forms", HP.page to pageWorkflowForms, WAGG.workflowId to "review")
        assertRoute("/join/confdemo", HP.page to HMENU.pageJoin, HP.client to "confdemo")
        assertRoute("/operator", HP.page to HMENU.pageOperator)
        assertRoute("/operator/boot-checks", HP.page to HMENU.pageBootChecks)
        assertRoute("/operator/cache", HP.page to HMENU.pageCacheState)
        assertRoute("/operator/tables", HP.page to HMENU.pageDbTables)
        assertRoute("/operator/env", HP.page to HMENU.pageEnv)
        assertRoute("/operator/system", HP.page to HMENU.pageSystemInfo)
        assertRoute("/operator/files", HP.page to HMENU.pageFileStore)
        assertRoute("/operator/fragments", HP.page to HMENU.pageFragmentsCheck)
        assertRoute("/operator/cfacts", HP.page to HMENU.pageCfacts)
        assertRoute("/test/become-user", HP.page to HMENU.pageBecomeUser)
        assertRoute("/test/simulations", HP.page to HMENU.pageSimulations)
        assertRoute("/debug", HP.page to HMENU.pageDebug)
        assertRoute("/debug/state", HP.page to HMENU.pageDebug, debugToolParam to debugToolState)
    }

    @Test
    fun refinementsRideTheQueryAndTheTokenTheFragment() {
        assertRoute(
            "/forms/gd.fd.acme.a/edit?task=review&from=forms",
            HP.page to pageSurveyEdit, HP.gedra to "gd.fd.acme.a", HP.edit to "1", HP.task to "review", HP.from to "forms",
        )
        // A value that is not the path's own (`edit=0`) is a refinement of the view, not the edit mode.
        assertRoute("/forms/gd.fd.acme.a?edit=0", HP.page to pageSurveyEdit, HP.gedra to "gd.fd.acme.a", HP.edit to "0")
        assertRoute("/invite#token=abc", HP.page to HMENU.pageInvite, HMENU.inviteTokenParam to "abc")
        // Any other key in the fragment is not a param.
        assertEquals(mapOf(HP.page to HMENU.pageDocs, HP.doc to "a"), readBack("/docs/a#u=1"))
    }

    @Test
    fun aDocumentsSectionIsItsAnchorAndNoOtherPageHasOne() {
        assertRoute("/docs/code-guide#naming", HP.page to HMENU.pageDocs, HP.doc to "code-guide", HP.section to "naming")
        // An anchor elsewhere is the browser's alone: a jump to one changes no param, so it tells no page.
        assertEquals(mapOf(HP.page to HMENU.pageForms), readBack("/forms#naming"))
        assertEquals(emptyMap(), readBack("#naming"))
        // A section written as a query param (before it was the anchor) still reads as one.
        assertEquals(mapOf(HP.section to "naming", HP.page to HMENU.pageDocs, HP.doc to "a"), readBack("/docs/a?section=naming"))
    }

    @Test
    fun aParamlessPageIsInferredAsTheRouterAlwaysInferredIt() {
        // The catalog's own writes leave the page out: an endpoint is the catalog's, a document the documents page's.
        assertEquals("/catalog?m=GET&p=%2Fkda%2Fhealth", routeHref(listOf(HP.method to "GET", HP.path to "/kda/health")))
        assertEquals("/docs/a", routeHref(listOf(HP.doc to "a")))
        // A page missing what its path needs, or one with no route, keeps home's path and says the page.
        assertRoute("/?page=editForm", HP.page to pageEditForm)
        assertRoute("/?page=elsewhere&x=1", HP.page to "elsewhere", "x" to "1")
    }

    @Test
    fun aPathSegmentIsEncodedAndKeepsAColon() {
        assertRoute("/clients/acme:sandbox", HP.page to HMENU.pageClients, HP.client to "acme:sandbox")
        assertRoute("/docs/a%2Fb%20c", HP.page to HMENU.pageDocs, HP.doc to "a/b c")
        // A malformed escape someone typed is read as written rather than thrown.
        assertEquals(mapOf(HP.page to HMENU.pageUsers, HP.user to "%zz"), readBack("/users/%zz"))
    }

    @Test
    fun aPathNoRouteMatchesIsNotFound() {
        assertEquals(mapOf(HP.page to pageNotFound), readBack("/nowhere"))
        assertEquals(mapOf(HP.page to pageNotFound), readBack("/forms/a/b/c"))
        // The query does not choose the page when the path has none.
        assertEquals(mapOf(HP.page to pageNotFound), readBack("/operator/nothing?page=forms"))
    }

    @Test
    fun anAddressFromBeforePathsIsRewrittenToItsPath() {
        assertEquals("/docs/a", legacyHashUrl("page=docs&doc=a"))
        assertEquals("/catalog?m=GET&p=%2Fkda%2Fhealth", legacyHashUrl("m=GET&p=%2Fkda%2Fhealth"))
        assertEquals("/docs/a", legacyHashUrl("doc=a"))
        assertEquals("/invite#token=abc", legacyHashUrl("page=invite&token=abc"))
        assertEquals("/operator/boot-checks?from=operator", legacyHashUrl("page=bootChecks&from=operator"))
        assertEquals("/docs/code-guide#naming", legacyHashUrl("page=docs&doc=code-guide&section=naming"))
        // An anchor, or a token alone, is not an old address.
        assertNull(legacyHashUrl("section-two"))
        assertNull(legacyHashUrl("token=abc"))
        assertNull(legacyHashUrl(""))
    }

    @Test
    fun homeNamingItsPageInTheQueryIsRewrittenToItsPath() {
        // The form the backend writes a page in (a simulation's start page), since it does not know the paths.
        assertEquals("/forms/new?from=forms", pageQueryUrl("/", "page=newForm&from=forms", ""))
        assertEquals("/clients/acme", pageQueryUrl("", "page=clients&c=acme", ""))
        assertEquals("/invite#token=abc", pageQueryUrl("/", "page=invite", "token=abc"))
        // Not home, no page, or a page with no path of its own: left as it is, which shows the page anyway.
        assertNull(pageQueryUrl("/forms", "page=users", ""))
        assertNull(pageQueryUrl("/", "q=x", ""))
        assertNull(pageQueryUrl("/", "page=elsewhere", ""))
        assertNull(pageQueryUrl("/", "page=editForm", ""))
    }
}
