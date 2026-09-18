package com.dynamicruntime.common.user

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.util.getOptLong
import com.dynamicruntime.common.util.getOptStr

/** Attribute keys of a [UserChoice]'s wire form ([UserChoice.toInfo]). Each name matches its value. */
@Suppress("ConstPropertyName")
object UCF {
    const val userId = "userId"
    const val client = "client"
    const val persona = "persona"
    const val personId = "personId"
    const val name = "name"
    const val isCurrent = "isCurrent"
    const val isDefault = "isDefault"
}

/**
 * One of the users a signed-in person may act as (issue #749): what the account menu lists and what
 * `switchUser` takes. Only a **registered, enabled** user is ever offered -- an unclaimed one an administrator
 * provisioned is not the person's to switch into until they prove it -- so neither state needs a field here.
 *
 * In the kernel so the frontend parses the same shape the backend serves and labels it by the same rule.
 */
data class UserChoice(
    val userId: Long,
    val client: String,
    val persona: String,
    /** The UAT batch discriminator (issue #747); empty for the ordinary user. */
    val personId: String = "",
    /** The user's real-world name, when it has one. */
    val name: String? = null,
    /** Whether this is the user the session is acting as. */
    val isCurrent: Boolean = false,
    /** Whether this is the identity's chosen default (`setDefaultUser`), as distinct from the fallback rule. */
    val isDefault: Boolean = false,
) {
    /**
     * The full label: the client and persona, the personId when there is one, and the name when there is one --
     * `acme / Admin`, `acme / Member 2 -- Ada Lovelace`. Client first because it is what most often differs
     * between a person's users; the name last because it usually does not. What a tooltip says; the bar shows
     * the shorter `qualifierWithin`. Pure, covered under `jsNodeTest`.
     */
    fun label(): String {
        val shownPersona = PERSONA.label(persona)
        val key = if (personId.isEmpty()) "$client / $shownPersona" else "$client / $shownPersona $personId"
        val shown = name?.trim()?.ifEmpty { null } ?: return key
        return "$key -- $shown"
    }

    /**
     * What tells this user from the others in [siblings], the person's users (issue #749): the client when
     * they span clients, the persona when they differ in persona **or any of them carries a personId** (so a
     * batch reads `Member`, `Member B` rather than a bare `B`), and the personId when there is one -- joined
     * with ` · `. Never empty for a list of two or more, since the key is unique across a person's users; empty
     * for a person with one user, who has nothing to tell apart. Pure, covered under `jsNodeTest`.
     */
    fun qualifierWithin(siblings: List<UserChoice>): String {
        if (siblings.size < 2) return ""
        val personaShown = siblings.any { it.persona != persona } || siblings.any { it.personId.isNotEmpty() }
        // The persona and the personId read as one term (`Member 2`), as they do in the full label.
        val personaPart = listOfNotNull(if (personaShown) PERSONA.label(persona) else null, personId.ifEmpty { null })
        val parts = buildList {
            if (siblings.any { it.client != client }) add(client)
            if (personaPart.isNotEmpty()) add(personaPart.joinToString(" "))
        }
        return parts.joinToString(" · ")
    }

    fun toInfo(): Map<String, Any?> = buildMap {
        put(UCF.userId, userId)
        put(UCF.client, client)
        put(UCF.persona, persona)
        if (personId.isNotEmpty()) put(UCF.personId, personId)
        if (name != null) put(UCF.name, name)
        if (isCurrent) put(UCF.isCurrent, true)
        if (isDefault) put(UCF.isDefault, true)
    }

    companion object {
        /** Schema type name for [toInfo]. */
        const val infoTypeName = "UserChoice"

        /** The mirror of [toInfo]; a map missing its id is not a choice and yields null. */
        fun fromInfo(info: Map<String, Any?>): UserChoice? = UserChoice(
            userId = info.getOptLong(UCF.userId) ?: return null,
            client = info.getOptStr(UCF.client) ?: return null,
            persona = info.getOptStr(UCF.persona) ?: PERSONA.member,
            personId = info.getOptStr(UCF.personId) ?: "",
            name = info.getOptStr(UCF.name),
            isCurrent = info[UCF.isCurrent] == true,
            isDefault = info[UCF.isDefault] == true,
        )

        /** Defines the [infoTypeName] schema type (the shape of [toInfo]) on [builder], kept beside the serialization. */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(infoTypeName) {
                type = SCT.kObject
                property(UCF.userId, "The user's numeric id.", required = true) { type = SCT.integer }
                property(UCF.client, "The client the user belongs to.", required = true)
                property(UCF.persona, "The user's persona.", required = true)
                property(UCF.personId, "The UAT batch discriminator; absent for the ordinary user.")
                property(UCF.name, "The user's real-world name, when it has one.")
                property(UCF.isCurrent, "Whether the session is acting as this user.") { type = SCT.boolean }
                property(UCF.isDefault, "Whether this is the identity's chosen default user.") { type = SCT.boolean }
            }
        }
    }
}
