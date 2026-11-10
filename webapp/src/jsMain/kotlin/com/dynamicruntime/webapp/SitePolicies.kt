package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.footer
import web.cssom.ClassName

/** A link to one of the deployment's site policies (issue #1711): what it is called, and the documents page that shows it. */
class SitePolicyLink(val label: String, val href: String)

/** The deployment's About page (issue #1769): its document's id, and its title, which its link says. */
class SiteAbout(val docId: String, val title: String)

/** The About page the home config names, or null when it names none or its title is missing. Pure. */
fun siteAboutOf(docId: String?, title: String?): SiteAbout? {
    val id = docId?.ifBlank { null } ?: return null
    return SiteAbout(id, title?.ifBlank { null } ?: return null)
}

/**
 * The links to the deployment's privacy policy and terms of service (issue #1711), in that order, for those it has
 * ([ShellFacts.privacyPolicy], [ShellFacts.termsOfService]): empty on a deployment that names neither, and before the
 * shell has been told, so nothing is drawn. Each opens its document's page, which a signed-out visitor can read too. Pure,
 * covered under `jsNodeTest`.
 */
fun sitePolicyLinks(facts: ShellFacts): List<SitePolicyLink> = listOfNotNull(
    facts.privacyPolicy?.let { SitePolicyLink(SITEW.privacyPolicy, docHref(it)) },
    facts.termsOfService?.let { SitePolicyLink(SITEW.termsOfService, docHref(it)) },
)

/**
 * The words a page says its site policies with (issue #1711). The app's own, as a link's "What is this?" is: the
 * policies are the deployment's documents, and their names are what a reader looks for.
 */
@Suppress("ConstPropertyName")
object SITEW {
    const val privacyPolicy = "Privacy policy"
    const val termsOfService = "Terms of service"

    /** What leads the sign-in page's line of them, under the sign-in choices. */
    const val beforeSignIn = "Before you sign in:"

    /** What leads Profile's line of them, in its account panel. */
    const val profileLead = "This site's policies:"
}

/**
 * The footer's links (issue #1769): the deployment's About page first, by its title, then its site policies
 * ([sitePolicyLinks]). The About page is the footer's alone: the sign-in page and Profile list the policies only. Pure,
 * covered under `jsNodeTest`.
 */
fun siteFooterLinks(facts: ShellFacts): List<SitePolicyLink> =
    listOfNotNull(facts.about?.let { SitePolicyLink(it.title, docHref(it.docId)) }) + sitePolicyLinks(facts)

/**
 * The footer under every page (issues #1711, #1769): the deployment's About page, privacy policy, and terms of service
 * ([siteFooterLinks]), which Google's sign-in verification looks for on the home page, and which a visitor finds in the
 * same place on every other. Nothing at all where the deployment names none.
 */
val SiteFooter = FC<Props> {
    val links = siteFooterLinks(useShellFacts())
    if (links.isEmpty()) return@FC
    footer {
        className = ClassName("site-footer")
        sitePolicyLinkList(links)
    }
}

/** [links] as a line of links, separated by a dot, as the footer, the sign-in page, and Profile draw them. */
fun ChildrenBuilder.sitePolicyLinkList(links: List<SitePolicyLink>) {
    links.forEachIndexed { i, link ->
        if (i > 0) +" · "
        a {
            key = link.href.unsafeCast<Key>()
            href = link.href
            +link.label
        }
    }
}
