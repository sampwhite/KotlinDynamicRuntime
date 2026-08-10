package com.dynamicruntime.webapp

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.user.AFEAT
import com.dynamicruntime.common.user.AFLD
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
                features = mapOf(APP.obfuscateSensitiveErrors to true, APP.showErrorDetail to true),
                settings = mapOf(APP.idleBumpIntervalMs to 30_000),
            ),
        )
        assertTrue(cfg.obfuscateSensitiveErrors)
        assertTrue(cfg.showErrorDetail)
        assertEquals(30_000, cfg.idleBumpIntervalMs)
    }

    @Test
    fun appConfigDefaultsWhenKeysMissing() {
        val cfg = appConfigFrom(uiConfig())
        assertFalse(cfg.obfuscateSensitiveErrors)
        // Withheld unless the deployment says otherwise (issue #223): a missing flag must not be the reason
        // internals reach a real user's screen, and this is also the pre-first-fetch state.
        assertFalse(cfg.showErrorDetail)
        assertEquals(APP.defaultIdleBumpIntervalMs, cfg.idleBumpIntervalMs)
    }

    @Test
    fun appConfigFallsBackWhenIntervalMalformed() {
        val cfg = appConfigFrom(uiConfig(settings = mapOf(APP.idleBumpIntervalMs to "soon")))
        assertEquals(APP.defaultIdleBumpIntervalMs, cfg.idleBumpIntervalMs)
    }

    // ---- homeConfigFrom ----

    @Test
    fun homeConfigReadsLinksMenuLayoutAndUser() {
        val cfg = homeConfigFrom(
            uiConfig(
                features = mapOf(
                    HFEAT.topBar to true,
                    HFEAT.leftBar to false,
                    HFEAT.inlineLinks to true,
                    HFEAT.canManageUsers to true,
                ),
                state = mapOf(
                    HFLD.links to listOf(
                        mapOf(HFLD.id to "l1", HFLD.label to "Guide", HFLD.docId to "guide", HFLD.buildId to "b7"),
                    ),
                    HFLD.menu to listOf(
                        mapOf(HFLD.id to "m1", HFLD.label to "Profile", HFLD.page to "profile"),
                        mapOf(HFLD.id to "m2", HFLD.label to "Log out", HFLD.action to "logout"),
                    ),
                    HFLD.userInfo to sampleUserInfo,
                ),
            ),
        )
        assertEquals(true, cfg.layout.topBar)
        assertEquals(false, cfg.layout.leftBar)
        assertEquals(true, cfg.layout.inlineLinks)
        assertTrue(cfg.canManageUsers)

        assertEquals(1, cfg.links.size)
        assertEquals("guide", cfg.links[0].docId)
        assertEquals("b7", cfg.links[0].buildId)

        assertEquals(2, cfg.menu.size)
        assertEquals("profile", cfg.menu[0].page)
        assertNull(cfg.menu[0].action)
        assertEquals("logout", cfg.menu[1].action)
        assertNull(cfg.menu[1].page)

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
                    AFEAT.simulatedEmail to true,
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
        assertTrue(cfg.features.simulatedEmail)
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
        assertFalse(cfg.features.simulatedEmail)
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
                    AFEAT.simulatedEmail to false,
                ),
                state = mapOf(
                    AFLD.userInfo to sampleUserInfo,
                    AFLD.loginId to "ada@example.com",
                ),
            ),
        )
        assertTrue(cfg.features.hasPassword)
        assertTrue(cfg.features.canSetPassword)
        assertFalse(cfg.features.simulatedEmail)
        assertEquals("ada@example.com", cfg.loginId)
        assertEquals("Ada", cfg.user.publicName)
    }

    @Test
    fun profileConfigDefaultsWhenEmpty() {
        val cfg = profileConfigFrom(uiConfig())
        assertFalse(cfg.features.hasPassword)
        assertFalse(cfg.features.canSetPassword)
        assertFalse(cfg.features.simulatedEmail)
        assertEquals("", cfg.loginId)
        // An empty state yields the default (system) profile, which is not "logged in".
        assertFalse(UserProfile.fromUserInfo(emptyMap()).isLoggedIn)
    }
}
