package com.dynamicruntime.common

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the issue-#78 split on BOTH targets (JVM + JS): the schema *builder* now lives in the kernel and runs
 * against a lightweight [LiteCxt] (no backend `KdrCxt`), so a shared data class like [UserProfile] can keep its
 * `defineInfoType` schema definition co-located and still be kernel code. Also the drift guard: the schema
 * built by `defineInfoType` accepts what `toUserInfo()` emits, and `fromUserInfo` inverts `toUserInfo`.
 */
class CxtSchemaBuilderTest {

    private val sample = UserProfile(
        authId = "7", userId = 7L, account = "local", roles = setOf("user", "admin"),
        publicName = "Ada", hasPassword = true,
    )

    @Test
    fun schemaBuilderRunsInKernelAndAcceptsTheSerialization() {
        // Build the UserInfo type with only a LiteCxt -- no KdrCxt in sight.
        val builder = SchTypesBuilder(LiteCxt(), "user")
        UserProfile.defineInfoType(builder)
        val types = parseSchemaTypes(builder.defs)
        val userInfo = types.getValue("user.UserInfo")

        assertTrue(validate(userInfo, sample.toUserInfo()).isEmpty(), "toUserInfo() must satisfy defineInfoType")
        // A missing required field (authId) must fail -- confirms the type is real, not vacuous.
        assertTrue(validate(userInfo, sample.toUserInfo() - "authId").isNotEmpty())
    }

    @Test
    fun userInfoRoundTrips() {
        val back = UserProfile.fromUserInfo(sample.toUserInfo())
        assertEquals(sample.authId, back.authId)
        assertEquals(sample.userId, back.userId)
        assertEquals(sample.account, back.account)
        assertEquals(sample.roles, back.roles)
        assertEquals(sample.publicName, back.publicName)
        assertEquals(sample.hasPassword, back.hasPassword)
    }
}
