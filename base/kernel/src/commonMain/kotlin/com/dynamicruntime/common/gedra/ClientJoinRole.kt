package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Joining a demo client (issue #1248): the wire names, the endpoints, and the deployment's switch.
 *
 * A demo client -- `acme`, `designdemo`, `confdemo` -- declares the roles a visitor may join it as
 * ([ClientDef.joinRoles]); a visitor signed in to `public` joins as one, getting a user of their own identity in that
 * one shared client. Offered only where the deployment says so ([offeredKey], which the `reference` flavor sets), so a
 * node that merely carries a demo client never offers it.
 */
@Suppress("ConstPropertyName")
object JOIN {
    /** The endpoints' schema namespace. */
    const val namespace = "kdr.clientJoin"

    /** The clients the caller may join, each with its roles and the caller's user in each it holds. */
    const val joinablePath = "/user/self/joinable"

    /** Joins a client as one of its roles, and moves the session to that user. */
    const val joinPath = "/user/self/join"

    /**
     * The instance-config key that offers joining on this deployment (issue #1248), set by the `reference` flavor.
     * Without it the join endpoints answer as absent.
     */
    const val offeredKey = "publicJoinOffered"

    // Wire names.
    const val client = "client"
    const val clientName = "clientName"
    const val description = "description"
    const val role = "role"
    const val roles = "roles"
    const val acknowledged = "acknowledged"
    const val userId = "userId"

    /**
     * On a role the caller joined: true when one of the client's administrators has since disabled their user, which
     * is not joined again.
     */
    const val removed = "removed"

    // A role's fields.
    const val name = "name"
    const val label = "label"
    const val persona = "persona"
    const val personaSuffix = "personaSuffix"
    const val userLabels = "userLabels"
    const val summary = "summary"

    const val roleTypeName = "ClientJoinRole"
    const val joinableTypeName = "JoinableClient"
    const val joinableRoleTypeName = "JoinableRole"
    const val resultTypeName = "ClientJoinResult"
}

/**
 * One role a visitor may join a demo client as (issue #1248): what joining gives them -- a user of their own identity
 * in the client, of [persona] (and so the persona's default roles, never more), with [personaSuffix] telling it apart
 * from the person's other users there, and carrying [userLabels] -- and how the Join page presents it: [label], and
 * [summary], a sentence on what the role does. [name] identifies the role on the wire.
 *
 * Two roles of one client differ by persona or suffix, so a person may hold both: in `confdemo`, an organizer and a
 * committee member are both administrators, the second suffixed `committee` and labeled so.
 */
data class ClientJoinRole(
    val name: String,
    val label: String,
    val persona: String,
    val summary: String,
    val personaSuffix: String = "",
    val userLabels: List<String> = emptyList(),
) {
    fun toJsonMap(): Map<String, Any?> = buildMap {
        put(JOIN.name, name)
        put(JOIN.label, label)
        put(JOIN.persona, persona)
        if (personaSuffix.isNotEmpty()) put(JOIN.personaSuffix, personaSuffix)
        if (userLabels.isNotEmpty()) put(JOIN.userLabels, userLabels)
        put(JOIN.summary, summary)
    }

    companion object {
        /** The roles [raw] holds -- a stored definition's list -- read leniently, as [ClientDef.fromInfo] reads the rest. */
        fun listFrom(raw: Any?): List<ClientJoinRole> = (raw as? List<*>).orEmpty().mapNotNull { item ->
            val m = item.toJsonMapOrEmpty()
            val name = m[JOIN.name].toOptStr() ?: return@mapNotNull null
            ClientJoinRole(
                name = name,
                label = m[JOIN.label].toOptStr() ?: name,
                persona = m[JOIN.persona].toOptStr().orEmpty(),
                summary = m[JOIN.summary].toOptStr().orEmpty(),
                personaSuffix = m[JOIN.personaSuffix].toOptStr().orEmpty(),
                userLabels = m[JOIN.userLabels].toJsonListOfStrings(),
            )
        }

        /** The properties of one role, as a definition's schema declares them. */
        fun declareProperties(builder: SchTypeBuilder) {
            builder.type = SCT.kObject
            builder.property(JOIN.name, "The role's id, unique within the client.", required = true)
            builder.property(JOIN.label, "The role as the Join page names it.", required = true)
            builder.property(JOIN.persona, "The persona a user joining as the role takes (member or admin).", required = true)
            builder.property(JOIN.personaSuffix, "Tells the user apart from the person's other users of the persona there.")
            builder.property(JOIN.userLabels, "The user labels a user joining as the role carries.") {
                type = SCT.array
                items { type = SCT.string }
            }
            builder.property(JOIN.summary, "A sentence on what the role does.", required = true)
        }
    }
}
