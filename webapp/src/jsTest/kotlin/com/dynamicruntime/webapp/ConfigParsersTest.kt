package com.dynamicruntime.webapp

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.PFRAG
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiCall
import com.dynamicruntime.common.uiblock.UiRoute
import com.dynamicruntime.common.user.AFEAT
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.webasset.WAS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the four `UiConfig` -> typed-config mappers extracted out of their
 * suspend fetchers ([appConfigFrom], [homeConfigFrom], [authConfigFrom], [profileConfigFrom]). Each is a plain
 * map-in / typed-value-out transform, so it is testable with no server, browser, or DOM. The cases assert both
 * that populated "fields" read through and that per-field defaults apply when a key is missing or malformed.
 */
class ConfigParsersTest {

    /** A [UiConfig] with a throwaway fragment ref (the mappers pass it through untouched). */
    private fun uiConfig(
        features: Map<String, Any?> = emptyMap(),
        settings: Map<String, Any?> = emptyMap(),
        state: Map<String, Any?> = emptyMap(),
    ) = UiConfig(FragmentRef("frag", "b1"), features, settings, state)

    private val sampleUserInfo = mapOf(
        UPF.authId to "auth-9",
        UPF.userId to 9L,
        UPF.client to "acme",
        UPF.roles to listOf("admin"),
        UPF.publicName to "Ada",
    )

    // ---- appConfigFrom ----

    @Test
    fun appConfigReadsFeaturesAndSettings() {
        val cfg = appConfigFrom(
            uiConfig(
                features = mapOf(
                    APP.obfuscateSensitiveErrors to true,
                    APP.showErrorDetail to true,
                    APP.allowDebugPages to true,
                    APP.hasTestAccess to true,
                    APP.isEnvAuthed to true,
                    APP.envAuthDebug to true,
                    APP.publicTestDeployment to true,
                ),
                settings = mapOf(APP.idleBumpIntervalMs to 30_000),
            ),
        )
        assertTrue(cfg.obfuscateSensitiveErrors)
        assertTrue(cfg.showErrorDetail)
        assertTrue(cfg.allowDebugPages)
        assertTrue(cfg.hasTestAccess)
        assertTrue(cfg.isEnvAuthed)
        assertTrue(cfg.envAuthDebug)
        assertTrue(cfg.publicTestDeployment)
        assertEquals(30_000, cfg.idleBumpIntervalMs)
    }

    @Test
    fun appConfigDefaultsWhenKeysMissing() {
        val cfg = appConfigFrom(uiConfig())
        assertFalse(cfg.obfuscateSensitiveErrors)
        // Withheld unless the deployment says otherwise (issue #223): a missing flag must not be the reason
        // internals reach a real user's screen, and this is also the pre-first-fetch state.
        assertFalse(cfg.showErrorDetail)
        // Off until the backend says otherwise: a route that breaks the app on purpose must never appear
        // because a config fetch had not landed yet (issue #227).
        assertFalse(cfg.allowDebugPages)
        // No test access until the backend says so, so a test-only debug tool is not offered early (issues #517,
        // #1149).
        assertFalse(cfg.hasTestAccess)
        // The debug state, likewise off until the backend authorizes it (issue #517).
        assertFalse(cfg.envAuthDebug)
        // No testing band until the backend says this is a public test deployment (issue #1150).
        assertFalse(cfg.publicTestDeployment)
        assertEquals(APP.defaultIdleBumpIntervalMs, cfg.idleBumpIntervalMs)
    }

    @Test
    fun appConfigFallsBackWhenIntervalMalformed() {
        val cfg = appConfigFrom(uiConfig(settings = mapOf(APP.idleBumpIntervalMs to "soon")))
        assertEquals(APP.defaultIdleBumpIntervalMs, cfg.idleBumpIntervalMs)
    }

    // ---- homeConfigFrom ----

    /**
     * The shell's config names the pages' copy files after its own (issue #1337): the first is the shell's copy, the
     * rest each page's by file id, and a config naming one file has none -- and the explanation in a namespace is its
     * `whatIsThis` key, with any pull resolved in the same file, or nothing.
     */
    @Test
    fun homeConfigNamesThePagesCopyFilesAfterItsOwn() {
        val home = FragmentRef(HFRAG.home, "h1")
        val clients = FragmentRef(PFRAG.clients, "c1")
        val reports = FragmentRef(PFRAG.reports, "r1")
        val cfg = homeConfigFrom(UiConfig(home, emptyMap(), emptyMap(), emptyMap(), listOf(home, clients, reports)))
        assertEquals(HFRAG.home, cfg.fragment.fileId)
        assertEquals(setOf(PFRAG.clients, PFRAG.reports), cfg.pageFragments.keys)
        assertEquals("c1", cfg.pageFragments.getValue(PFRAG.clients).buildId)
        assertTrue(homeConfigFrom(uiConfig()).pageFragments.isEmpty())

        val copy = Copy(
            mapOf(
                PFRAG.filesNs to mapOf(HFRAG.explanation to $$"Files are images. ${@t(\"files.more\")}", "more" to "See above."),
                PFRAG.sandboxNs to mapOf("title" to "Sandbox"),
            ),
        )
        assertEquals("Files are images. See above.", copy.explanation(PFRAG.filesNs))
        assertEquals("", copy.explanation(PFRAG.sandboxNs))
        assertEquals("", Copy.empty.explanation(PFRAG.storedConfigNs))
    }

    @Test
    fun homeConfigReadsLinksMenuLayoutAndUser() {
        val cfg = homeConfigFrom(
            uiConfig(
                features = mapOf(
                    HFEAT.topBar to true,
                    HFEAT.leftBar to false,
                    HFEAT.inlineLinks to true,
                    HFEAT.canManageUsers to true,
                    HFEAT.hasSurvey to true,
                    HFEAT.administersClient to true,
                ),
                state = mapOf(
                    HFLD.links to listOf(
                        mapOf(HFLD.id to "l1", HFLD.label to "Guide", HFLD.docId to "guide", HFLD.buildId to "b7", HFLD.description to "The guide."),
                    ),
                    HFLD.menu to listOf(
                        mapOf(HFLD.id to "m1", HFLD.label to "Profile", UIB.action to "profile"),
                        mapOf(HFLD.id to "m2", HFLD.label to "Log out", UIB.action to listOf("logout")),
                    ),
                    HFLD.userInfo to sampleUserInfo,
                ),
            ),
        )
        assertEquals(true, cfg.layout.topBar)
        assertEquals(false, cfg.layout.leftBar)
        assertEquals(true, cfg.layout.inlineLinks)
        assertTrue(cfg.canManageUsers)
        // The client declares a survey (issue #695); absent reads as false like the other flags.
        assertTrue(cfg.hasSurvey)
        assertFalse(homeConfigFrom(uiConfig(features = emptyMap(), state = emptyMap())).hasSurvey)
        // Whether their listings reach beyond their own rows (issue #1091); absent reads as false, as an older
        // backend that does not say leaves a caller listing their own.
        assertTrue(cfg.administersClient)
        assertFalse(homeConfigFrom(uiConfig(features = emptyMap(), state = emptyMap())).administersClient)
        // The client's own web assets (issue #1130) ride the same config; none, from a backend that sends none.
        assertEquals(
            mapOf(WAS.favicon to "favicon:aa"),
            homeConfigFrom(uiConfig(features = emptyMap(), state = mapOf(HFLD.webAssets to mapOf(WAS.favicon to "favicon:aa")))).webAssets,
        )
        assertEquals(emptyMap(), homeConfigFrom(uiConfig(features = emptyMap(), state = emptyMap())).webAssets)
        // Its own flag, not the manage-users one beside it: an administrator in `public` has that and not this.
        val publicAdmin = homeConfigFrom(uiConfig(features = mapOf(HFEAT.canManageUsers to true), state = emptyMap()))
        assertTrue(publicAdmin.canManageUsers)
        assertFalse(publicAdmin.administersClient)
        // An editor (issue #1432): browses users and reads the whole client's forms, administering nothing.
        val editor = homeConfigFrom(uiConfig(features = mapOf(HFEAT.browsesUsers to true, HFEAT.seesClientForms to true), state = emptyMap()))
        assertTrue(editor.browsesUsers && editor.seesClientForms)
        assertFalse(editor.canManageUsers || editor.administersClient)
        // The Users page: an editor browses it read-only; an administrator edits; a member is not offered it.
        assertTrue(mayBrowseUsers(editor) && usersReadOnly(editor))
        val userAdmin = homeConfigFrom(uiConfig(features = mapOf(HFEAT.canManageUsers to true, HFEAT.browsesUsers to false), state = emptyMap()))
        assertTrue(mayBrowseUsers(userAdmin))
        assertFalse(usersReadOnly(userAdmin))
        val plain = homeConfigFrom(uiConfig(features = emptyMap(), state = emptyMap()))
        assertFalse(mayBrowseUsers(plain) || usersReadOnly(plain))
        assertFalse(mayBrowseUsers(null))
        // Creating a form for another user (issue #1433): an administrator's or an editor's, and no member's.
        assertTrue(createsFormsForOthers(editor) && createsFormsForOthers(userAdmin))
        assertFalse(createsFormsForOthers(plain))
        assertFalse(homeConfigFrom(uiConfig(features = emptyMap(), state = emptyMap())).seesClientForms)

        // What the shell tells the pages (issue #1091): the caller's fact, and the label of the menu entry that opens
        // the forms listing -- whichever entry it is, by the page it routes to, and by the words the menu has for it.
        fun facts(features: Map<String, Any?>, vararg menu: Map<String, Any?>) =
            shellFactsOf(homeConfigFrom(uiConfig(features = features, state = mapOf(HFLD.menu to menu.toList()))))
        val member = facts(emptyMap(), mapOf(HFLD.id to "forms", HFLD.label to "My forms", UIB.action to "forms"))
        assertEquals(ShellFacts(seesClientForms = false, formsEntryLabel = "My forms"), member)
        val admin = facts(
            mapOf(HFEAT.seesClientForms to true),
            mapOf(HFLD.id to "users", HFLD.label to "Users", UIB.action to "users"),
            mapOf(HFLD.id to "clientForms", HFLD.label to "Audits", UIB.action to "forms"),
        )
        assertEquals(ShellFacts(seesClientForms = true, formsEntryLabel = "Audits"), admin)
        // A "What is this?" link's words are the shell's copy (issue #1330), told once that copy has arrived.
        val config = homeConfigFrom(uiConfig(features = emptyMap(), state = emptyMap()))
        val withCopy = shellFactsOf(config, Copy(mapOf(HFRAG.whatIsThisNs to mapOf(HFRAG.whatIsThisLink to "Why?", HFRAG.whatIsThisHide to "Close"))))
        assertEquals("Why?" to "Close", withCopy.whatIsThisLink to withCopy.whatIsThisHide)
        assertEquals(null, shellFactsOf(config).whatIsThisLink)
        assertEquals("Audits", formsListingName(admin))
        // No entry for the page (a client hid it), or one with no words: the name falls back on the caller's fact.
        assertEquals(null, facts(emptyMap(), mapOf(HFLD.id to "users", HFLD.label to "Users", UIB.action to "users")).formsEntryLabel)
        assertEquals(null, facts(emptyMap(), mapOf(HFLD.id to "forms", HFLD.label to " ", UIB.action to "forms")).formsEntryLabel)
        // A call is not a route to the page, whatever it is named.
        assertEquals(null, facts(emptyMap(), mapOf(HFLD.id to "x", HFLD.label to "X", UIB.action to listOf("forms"))).formsEntryLabel)

        assertEquals(1, cfg.links.size)
        assertEquals("guide", cfg.links[0].docId)
        assertEquals("b7", cfg.links[0].buildId)
        assertEquals("The guide.", cfg.links[0].description)
        // Absent, as from an older node that listed every document it sent; said, as sent (issue #1136).
        assertTrue(cfg.links[0].listed)
        val unlisted = homeConfigFrom(
            uiConfig(features = emptyMap(), state = mapOf(HFLD.links to listOf(mapOf(HFLD.id to "p", HFLD.listed to false)))),
        )
        assertFalse(unlisted.links.single().listed)

        assertEquals(2, cfg.menu.size)
        // One field, two shapes (issue #483): a string is a route, an array is a call. The old pair of
        // nullable fields could be both or neither; this cannot.
        assertEquals("profile", (cfg.menu[0].action as UiRoute).page)
        val call = cfg.menu[1].action as UiCall
        assertEquals("logout", call.def.name)
        assertTrue(call.args.isEmpty())

        assertEquals("Ada", cfg.user.publicName)
        assertTrue(cfg.user.isLoggedIn)
    }

    @Test
    fun homeConfigDefaultsWhenEmpty() {
        val cfg = homeConfigFrom(uiConfig())
        assertTrue(cfg.links.isEmpty())
        assertTrue(cfg.menu.isEmpty())
        assertFalse(cfg.layout.topBar)
        assertFalse(cfg.layout.leftBar)
        assertFalse(cfg.layout.inlineLinks)
        assertFalse(cfg.canManageUsers)
    }

    // ---- authConfigFrom ----

    @Test
    fun authConfigReadsFeaturesAndState() {
        val cfg = authConfigFrom(
            uiConfig(
                features = mapOf(
                    AFEAT.registration to true,
                    AFEAT.codeLogin to true,
                    AFEAT.passwordLogin to false,
                    AFEAT.googleLogin to true,
                    AFEAT.codeReadBack to true,
                ),
                state = mapOf(
                    AFLD.userInfo to sampleUserInfo,
                    AFLD.googleClientId to "client-123",
                ),
            ),
        )
        assertTrue(cfg.features.registration)
        assertTrue(cfg.features.codeLogin)
        assertFalse(cfg.features.passwordLogin)
        assertTrue(cfg.features.googleLogin)
        assertTrue(cfg.features.codeReadBack)
        assertEquals("client-123", cfg.googleClientId)
        assertTrue(cfg.user.isLoggedIn)
    }

    @Test
    fun authConfigDefaultsWhenEmpty() {
        val cfg = authConfigFrom(uiConfig())
        assertFalse(cfg.features.registration)
        assertFalse(cfg.features.codeLogin)
        assertFalse(cfg.features.passwordLogin)
        assertFalse(cfg.features.googleLogin)
        assertFalse(cfg.features.codeReadBack)
        assertEquals("", cfg.googleClientId)
        assertFalse(cfg.user.isLoggedIn)
    }

    // ---- profileConfigFrom ----

    @Test
    fun profileConfigReadsFeaturesAndLoginId() {
        val cfg = profileConfigFrom(
            uiConfig(
                features = mapOf(
                    AFEAT.hasPassword to true,
                    AFEAT.canSetPassword to true,
                    AFEAT.codeReadBack to false,
                ),
                state = mapOf(
                    AFLD.userInfo to sampleUserInfo,
                    AFLD.loginId to "ada@example.com",
                ),
            ),
        )
        assertTrue(cfg.features.hasPassword)
        assertTrue(cfg.features.canSetPassword)
        assertFalse(cfg.features.codeReadBack)
        assertEquals("ada@example.com", cfg.loginId)
        assertEquals("Ada", cfg.user.publicName)
    }

    @Test
    fun profileConfigDefaultsWhenEmpty() {
        val cfg = profileConfigFrom(uiConfig())
        assertFalse(cfg.features.hasPassword)
        assertFalse(cfg.features.canSetPassword)
        assertFalse(cfg.features.codeReadBack)
        assertEquals("", cfg.loginId)
        // An empty state yields the default (system) profile, which is not "logged in".
        assertFalse(UserProfile.fromUserInfo(emptyMap()).isLoggedIn)
    }

    /**
     * The env-auth pair (issue #360). Both default false when absent, for the same reason `showErrorDetail`
     * does: a config that has not arrived must never be why an internal affordance appears.
     *
     * The case that matters is the third -- suppressed. `isEnvAuthed` false with `envAuthSuppressible` true is
     * what keeps the control that restores the session on screen, and reading either flag alone would lose it.
     */
    @Test
    fun envAuthFlagsReadThroughIndependently() {
        val absent = appConfigFrom(uiConfig())
        assertFalse(absent.isEnvAuthed)
        assertFalse(absent.envAuthSuppressible)

        val on = appConfigFrom(
            uiConfig(features = mapOf(APP.isEnvAuthed to true, APP.envAuthSuppressible to true)),
        )
        assertTrue(on.isEnvAuthed)
        assertTrue(on.envAuthSuppressible)

        val suppressed = appConfigFrom(
            uiConfig(features = mapOf(APP.isEnvAuthed to false, APP.envAuthSuppressible to true)),
        )
        assertFalse(suppressed.isEnvAuthed)
        assertTrue(suppressed.envAuthSuppressible)
    }
}
