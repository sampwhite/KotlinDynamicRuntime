package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GIF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.CDEP
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The editor (issue #1432): sees every user and form of its client and edits forms, administering nothing -- no user
 * edits, no user creation, no client configuration -- and edits itself. Over the shared instance, with clients of its
 * own.
 */
class EditorRoleTest : StringSpec({
    val cxt = TestInstances.default("editor1432")

    fun defineClient(client: String) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("editor", client).also { it.userId = 14320L }, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    val mine = "editors1432"
    val theirs = "noteditors1432"
    defineClient(mine)
    defineClient(theirs)

    fun editor(email: String, client: String = mine): TestUser =
        TestUser.create(cxt, email, capabilities = listOf(ROLE.editor), userClient = client)

    fun newForm(user: TestUser, name: String): String = user.postItem(
        GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to name)))),
    )[GDF.gedraId].toOptStr()!!

    fun rename(gedraId: String, name: String) = mapOf(
        GPF.targets to mapOf(
            GedraDataType.formDoc.name to listOf(
                mapOf(
                    GDF.gedraId to gedraId,
                    GPF.edits to listOf(
                        mapOf(GED.action to GedraEditAction.addOrReplace.name, GE.traitId to GT.name, GE.data to mapOf(GT.nameField to name)),
                    ),
                ),
            ),
        ),
    )

    fun nameOf(user: TestUser, gedraId: String): Any? = user.getItem(GEP.formDoc, mapOf(GDF.gedraId to gedraId))[GDF.entries]
        .toJsonListOfMaps().single { it[GE.traitId] == GT.name }[GE.data].toJsonMapOrEmpty()[GT.nameField]

    fun listedIds(user: TestUser): List<Any?> = user.getItems(GEP.formDocs).map { it[GDF.gedraId] }

    fun nameOfRow(row: Map<String, Any?>): Any? = row[GDF.entries].toJsonListOfMaps()
        .firstOrNull { it[GE.traitId] == GT.name }?.get(GE.data).toJsonMapOrEmpty()[GT.nameField]

    "the role is held outright: an admin implies it, an operator does not" {
        RoleLadder.satisfies(setOf(ROLE.user, ROLE.editor), ROLE.editor) shouldBe true
        RoleLadder.satisfies(setOf(ROLE.user, ROLE.admin), ROLE.editor) shouldBe true
        RoleLadder.satisfies(setOf(ROLE.user, ROLE.operator), ROLE.editor) shouldBe false
        RoleLadder.satisfies(setOf(ROLE.user), ROLE.editor) shouldBe false
        // Nor does it reach anything a rung does.
        RoleLadder.satisfies(setOf(ROLE.user, ROLE.editor), ROLE.operator) shouldBe false
        RoleLadder.satisfies(setOf(ROLE.user, ROLE.editor), ROLE.admin) shouldBe false
        // Its persona, and the persona its roles imply.
        PERSONA.def(PERSONA.editor).shouldNotBeNull().defaultRoles shouldBe listOf(ROLE.user, ROLE.editor)
        PERSONA.defaultFor(listOf(ROLE.user, ROLE.editor)) shouldBe PERSONA.editor
        PERSONA.defaultFor(listOf(ROLE.user)) shouldBe PERSONA.member
        PERSONA.defaultFor(listOf(ROLE.user, ROLE.editor, ROLE.admin)) shouldBe PERSONA.admin
    }

    "an editor reads its client's users in the directory, and none of another client's" {
        val ed = editor("reader@$mine.test")
        val colleague = TestUser.create(cxt, "colleague@$mine.test", userClient = mine)
        val stranger = TestUser.create(cxt, "stranger@$theirs.test", userClient = theirs)
        val listed = ed.getItems(CDEP.users).map { it[ADF.userId] }
        listed shouldContain colleague.userId
        listed shouldNotContain stranger.userId
        val found = ed.getItems(CDEP.userSearch, mapOf(EI.q to "colleague@")).map { it[ADF.userId] }
        found shouldContain colleague.userId
        ed.getItems(CDEP.userSearch, mapOf(EI.q to "stranger@")).map { it[ADF.userId] } shouldNotContain stranger.userId
        // An administrator passes the directory too; an operator, who holds no part of the role, does not, and nor does
        // an ordinary member.
        TestUser.create(cxt, "chief@$mine.test", level = ROLE.admin, userClient = mine).getItems(CDEP.users)
            .map { it[ADF.userId] } shouldContain colleague.userId
        TestUser.create(cxt, "ops@$mine.test", level = ROLE.operator, userClient = mine).expectError(EXC.notAuthorized, CDEP.users)
        colleague.expectError(EXC.notAuthorized, CDEP.users)
    }

    "an editor administers nothing: no user changes, no new users, no configuration" {
        val ed = editor("noadmin@$mine.test")
        val colleague = TestUser.create(cxt, "victim@$mine.test", userClient = mine)
        ed.expectError(EXC.notAuthorized, UADEP.users)
        ed.expectError(EXC.notAuthorized, UADEP.userCreate, mapOf(ADF.primaryId to "made@$mine.test"))
        ed.expectError(EXC.notAuthorized, UADEP.userInvite, mapOf(ADF.userId to colleague.userId))
        ed.expectError(EXC.notAuthorized, UADEP.userSetRoles, mapOf(ADF.userId to colleague.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)))
        ed.expectError(EXC.notAuthorized, UADEP.userSetName, mapOf(ADF.userId to colleague.userId, ADF.name to "Renamed"))
        ed.expectError(EXC.notAuthorized, UADEP.userDelete, args = mapOf(ADF.userId to colleague.userId), method = HttpMethod.DELETE)
        ed.expectError(EXC.notAuthorized, CFEP.bundles)
        ed.expectError(EXC.notAuthorized, CFD.cfactsPath)
        ed.expectError(EXC.notAuthorized, AKEY.adminKeys)
    }

    "an editor edits itself, but cannot label itself" {
        val ed = editor("self@$mine.test")
        ed.postData(AEP.profileSetName, mapOf(AFLD.name to "Edie Tor"))
        ed.getData(AEP.selfInfo)[UPF.userId].toOptLong() shouldBe ed.userId
        ed.expectError(EXC.notAuthorized, UADEP.userSetLabels, mapOf(ADF.userId to ed.userId, ADF.labels to listOf("reviewer")))
        // An administrator labels it.
        TestUser.create(cxt, "labeler@$mine.test", level = ROLE.admin, userClient = mine)
            .postData(UADEP.userSetLabels, mapOf(ADF.userId to ed.userId, ADF.labels to listOf("reviewer")))
        ed.getItems(CDEP.userSearch, mapOf(EI.q to "self@$mine")).single()[ADF.labels] shouldBe listOf("reviewer")
    }

    "an editor sees and edits every form of its client, with its owner, and none of another client's" {
        val ed = editor("forms@$mine.test")
        val author = TestUser.create(cxt, "author@$mine.test", userClient = mine)
        val outsider = TestUser.create(cxt, "outsider@$theirs.test", userClient = theirs)
        val authors = newForm(author, "Author's")
        val outsiders = newForm(outsider, "Outsider's")
        listedIds(ed) shouldContain authors
        listedIds(ed) shouldNotContain outsiders
        // Whose each is, which an ordinary member is not told.
        val owned = ed.getItems(GEP.formDocs, mapOf(EI.includeUsers to true)).single { it[GDF.gedraId] == authors }
        owned[GDF.owner].toJsonMapOrEmpty().isNotEmpty() shouldBe true
        // And it edits one: the patch is admitted by its reach.
        ed.postData(GEP.patch, rename(authors, "Edited by the editor"))
        nameOf(author, authors) shouldBe "Edited by the editor"
        // Another client's form is not there to edit.
        ed.expectError(EXC.notFound, GEP.formDoc, args = mapOf(GDF.gedraId to outsiders))
    }

    "an editor deletes its own forms and others' in no workflow, and creates them for others of its client" {
        val ed = editor("deleter@$mine.test")
        val author = TestUser.create(cxt, "kept@$mine.test", userClient = mine)
        // Another user's form in no workflow (issue #1452): the editor deletes it. (Refusing one in a workflow is
        // `OwnerOnlyTest`'s, whose client has one.)
        val theirsForm = newForm(author, "Not the editor's")
        ed.deleteData(GEP.formDoc, mapOf(GDF.gedraId to theirsForm))[GDF.gedraId] shouldBe theirsForm
        author.getItems(GEP.formDocs).map { it[GDF.gedraId] } shouldNotContain theirsForm
        // Its own it deletes.
        val own = newForm(ed, "The editor's own")
        ed.deleteData(GEP.formDoc, mapOf(GDF.gedraId to own))
        listedIds(ed) shouldNotContain own
        // An administrator deletes the author's.
        val authors = newForm(author, "The author's")
        TestUser.create(cxt, "remover@$mine.test", level = ROLE.admin, userClient = mine).deleteData(GEP.formDoc, mapOf(GDF.gedraId to authors))
        // It creates a form for another user of its client (issue #1433): the form is theirs, and -- in no workflow --
        // the editor may take it back (issue #1452), as when it was started for the wrong person.
        val forAuthor = ed.postItem(
            GEP.formDocCreate,
            mapOf(EI.user to author.userId.toString(), GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "For you")))),
        )[GDF.gedraId].toOptStr()!!
        author.getItems(GEP.formDocs).map { it[GDF.gedraId] } shouldContain forAuthor
        ed.deleteData(GEP.formDoc, mapOf(GDF.gedraId to forAuthor))
        author.getItems(GEP.formDocs).map { it[GDF.gedraId] } shouldNotContain forAuthor
        // And imports one for them, which is creating it by another door.
        val doc = mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "Imported for you"))))
        ed.postData(GEP.formDocImport, mapOf(EI.user to author.userId.toString(), GIF.data to doc))
        author.getItems(GEP.formDocs).any { nameOfRow(it) == "Imported for you" } shouldBe true
        // Not for a user of another client, whom its scope does not reach.
        val outsider = TestUser.create(cxt, "notreached@$theirs.test", userClient = theirs)
        ed.expectError(
            EXC.notFound, GEP.formDocCreate,
            mapOf(EI.user to outsider.userId.toString(), GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "Out")))),
        )
        ed.expectError(EXC.badInput, GEP.formDocImport, mapOf(EI.user to outsider.userId.toString(), GIF.data to doc))
            .toString() shouldContain "is within your access"
        // An ordinary member still creates for nobody else: its scope finds no one but itself.
        author.expectError(
            EXC.notFound, GEP.formDocCreate,
            mapOf(EI.user to ed.userId.toString(), GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "No")))),
        )
    }

    "an editor confined to an organization is confined, and one in public reaches only its own" {
        val chief = TestUser.create(cxt, "orgchief@$mine.test", level = ROLE.admin, userClient = mine)
        val ed = editor("orged@$mine.test")
        val inOrg = TestUser.create(cxt, "inorg@$mine.test", userClient = mine)
        val outOrg = TestUser.create(cxt, "outorg@$mine.test", userClient = mine)
        chief.postData(UADEP.userSetOrg, mapOf(ADF.userId to ed.userId, ADF.org to "east"))
        chief.postData(UADEP.userSetOrg, mapOf(ADF.userId to inOrg.userId, ADF.org to "east"))
        chief.postData(UADEP.userSetOrg, mapOf(ADF.userId to outOrg.userId, ADF.org to "west"))
        val east = newForm(inOrg, "East")
        val west = newForm(outOrg, "West")
        listedIds(ed) shouldContainAll listOf(east)
        listedIds(ed) shouldNotContain west
        ed.getItems(CDEP.users).map { it[ADF.userId] } shouldNotContain outOrg.userId

        // In `public`, the placeholder, a client role widens nothing.
        val publicEditor = editor("public-editor@example.org", client = CL.public)
        val publicOther = TestUser.create(cxt, "public-other@example.org", userClient = CL.public)
        val others = newForm(publicOther, "Public other's")
        listedIds(publicEditor) shouldNotContain others
    }

    "an administrator grants the role, an editor grants nothing, and an editor's API key acts as one" {
        val chief = TestUser.create(cxt, "granter@$mine.test", level = ROLE.admin, userClient = mine)
        val member = TestUser.create(cxt, "promoted@$mine.test", userClient = mine)
        chief.postData(UADEP.userSetRoles, mapOf(ADF.userId to member.userId, ADF.roles to listOf(ROLE.user, ROLE.editor)))
        member.getItems(CDEP.users).map { it[ADF.userId] } shouldContain chief.userId
        // A user created with the persona starts as one.
        val made = chief.postData(UADEP.userCreate, mapOf(ADF.primaryId to "made-editor@$mine.test", ADF.persona to PERSONA.editor))
        made[ADF.roles] shouldBe listOf(ROLE.user, ROLE.editor)

        // An editor's key reads the directory as the editor would, and is refused the administrator's surface.
        val key = member.postData(AKEY.selfCreate, mapOf(AKEY.name to "editor's script"))[AKEY.secret].toOptStr().shouldNotBeNull()
        val keyed = com.dynamicruntime.common.http.request.TestHttpClient(cxt.instanceConfig)
            .apply { setHeader(AKEY.authorizationHeader, "${AKEY.bearerScheme}$key") }
        keyed.sendGetRequest(CDEP.users).rptStatusCode shouldBe 200
        keyed.sendGetRequest(UADEP.users).rptStatusCode shouldBe EXC.notAuthorized
    }

    "the shell tells an editor it browses users and sees the client's forms" {
        val features = editor("shell@$mine.test").getData(HEP.homeUiConfig)[UIC.features].toJsonMapOrEmpty()
        features[HFEAT.browsesUsers] shouldBe true
        features[HFEAT.seesClientForms] shouldBe true
        features[HFEAT.canManageUsers] shouldBe false
        features[HFEAT.administersClient] shouldBe false
        val member = TestUser.create(cxt, "plainshell@$mine.test", userClient = mine).getData(HEP.homeUiConfig)[UIC.features]
            .toJsonMapOrEmpty()
        member[HFEAT.browsesUsers] shouldBe false
        member[HFEAT.seesClientForms] shouldBe false
    }

    "an editor opens no sandbox" {
        val features = editor("sandbox@$mine.test").getData(HEP.homeUiConfig)[UIC.features].toJsonMapOrEmpty()
        features[HFEAT.canOpenSandbox] shouldBe false
        editor("opener@$mine.test").expectError(EXC.notAuthorized, AEP.openSandbox, emptyMap())
    }
})
