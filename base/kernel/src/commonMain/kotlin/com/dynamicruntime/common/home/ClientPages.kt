package com.dynamicruntime.common.home

/**
 * A page a menu item, a card, or copy may open for a client's people: its [page] name, which an item's action gives, its
 * [path] under the app's root, which a link in Markdown gives after `/wa/`, and what it is.
 */
class ClientPage(val page: String, val path: String, val about: String)

/**
 * The pages a client's configuration may send its people to (issue #1869), for the menu and home cards guide, which lists
 * them: a page no menu item opens, such as the New form, was named nowhere a configuration's author could read, and the
 * configuration assistant guessed.
 *
 * The names and paths are the webapp's route table's, whose tests hold each of these to it; here, in the kernel, because
 * the guide is made by the backend. A component's own page, such as the configuration assistant's, is not among them, and
 * neither are the deployment's, or a page that needs more than its name to open (a form, a report).
 */
object ClientPages {
    val all: List<ClientPage> = listOf(
        ClientPage(HMENU.pageHome, "", "Home."),
        ClientPage(HMENU.pageNewForm, "forms/new", "The New form: starts a form, with the client's creation workflow. For anyone signed in."),
        ClientPage(HMENU.pageForms, "forms", "The forms list: a person's own, or every form for an administrator or an editor."),
        ClientPage(HMENU.pageWorkflows, "workflows", "The client's workflows, with how many forms are in each."),
        ClientPage(HMENU.pageFiles, "files", "The files forms hold."),
        ClientPage(HMENU.pageProfile, "profile", "The person's own account."),
        ClientPage(HMENU.pageDocs, "docs", "The documents the deployment lists."),
        ClientPage(HMENU.pageLogin, "login", "Logging in."),
        ClientPage(HMENU.pageRegister, "register", "Registering."),
        ClientPage(HMENU.pageClaim, "claim", "Claiming an account an administrator created."),
        ClientPage(HMENU.pageReports, "reports", "Reports. For an administrator."),
        ClientPage(HMENU.pageUsers, "users", "The client's users. For an administrator or an editor."),
        ClientPage(HMENU.pageClients, "clients", "The client's own page: its configuration. For an administrator."),
        ClientPage(HMENU.pageCustomers, "customers", "The customer's page. For an administrator of its root client."),
        ClientPage(HMENU.pageApiKeys, "api-keys", "API keys. For an administrator."),
        ClientPage(HMENU.pageCatalog, "catalog", "The endpoint catalog."),
        ClientPage(HMENU.pageCfacts, "operator/cfacts", "The client's facts. For an operator or an administrator."),
    )

    /** The page an action's string [name]s, or null when it names none of these. */
    fun forName(name: String): ClientPage? = all.firstOrNull { it.page == name }

    /** The address a link in Markdown opens [page] at: the app's root as copy writes it, whatever the app is served under. */
    fun address(page: ClientPage): String = "/wa/${page.path}"
}
