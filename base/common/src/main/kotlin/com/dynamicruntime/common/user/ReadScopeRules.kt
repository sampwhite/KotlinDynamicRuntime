package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.http.request.ROLE

/**
 * Decides which rows a caller may read (issue #225): the one place "how far does this person see?" is
 * answered, for **every** caller rather than only for administrators.
 *
 * It lives apart from [AdminRules] because the question is no longer an administrative one. While the only
 * scoped reads were the admin ones, `AdminRules.adminReadScope` was the honest name; an ordinary endpoint
 * author looking for a read scope and finding something called *admin* either concludes it does not apply to
 * them or copies the pattern by hand -- which is how two answers to one question get written. The widths are
 * a single ladder and belong behind a single name.
 *
 * The widths, narrowest first:
 *
 * | caller | reach |
 * |---|---|
 * | anyone else (an ordinary user) | their own rows |
 * | [ROLE.admin], with a primary organization | that organization, plus the client's org-less rows |
 * | [ROLE.admin] | their whole client |
 * | [ROLE.admin] + [ROLE.allClients] | everything |
 *
 * **Narrow by default is the whole design.** The alternative -- unrestricted unless something restricts it --
 * fails in the widening direction and does it silently: a query written without a scope returns every row and
 * looks perfectly healthy. This way the mistake surfaces as "I cannot see that", which somebody reports.
 */
object ReadScopeRules {
    /**
     * What [cxt]'s caller may read. Pass it to a query rather than deciding per endpoint, so a new endpoint
     * gets the rule by construction instead of by remembering it.
     *
     * The level and the scope are separate axes: a role says *what* may be done, this says *over whose rows*.
     * That is why an ordinary user is not "denied" here but *confined* -- refusing them is a job for the
     * section gate, which has already run by the time anything asks this.
     *
     * **The own-user width has no surface reaching it yet.** Every scoped read today is on a user-list
     * endpoint behind an administrative section, so in practice this returns one of the upper three; the
     * narrow branch is what the first ordinary endpoint over an owned table will get, and it is covered by
     * tests rather than by a caller. It is deliberately not left to that endpoint to invent: an author who has
     * to reach for `ReadScope.ofUser` by hand is an author who can forget to.
     */
    fun forCaller(cxt: KdrCxt): ReadScope = when (AdminRules.adminScope(cxt)) {
        // A primary organization narrows an administrator one width further. Most have none, and then this is
        // exactly the client scope it always was -- organizations are a per-client choice, so having none is
        // the ordinary case rather than a missing value.
        AdminScope.ownClient -> {
            val profile = cxt.userProfile
            val org = profile.org
            if (org == null) ReadScope.ofClient(profile.client) else ReadScope.ofOrg(profile.client, org)
        }
        // The capability outranks a primary organization. Someone who may reach every client is not confined
        // by which organization they happen to belong to -- different axes, and this is the wider.
        AdminScope.allClients -> ReadScope.unrestricted
        // Not an administrator: their own rows and nothing else. This branch once returned `unrestricted`,
        // reasoning that the section gate closes the admin surface to such a caller so there was no read left
        // to constrain. The reasoning was sound and the premise was false -- while the `admin` section
        // accepted the `allClients` capability alone (issue #237), a caller with no admin level reached those
        // endpoints and this handed them every client's rows. Failing closed costs nothing and does not depend
        // on another component staying correct.
        AdminScope.none -> ReadScope.ofUser(cxt.userProfile.userId)
    }
}
