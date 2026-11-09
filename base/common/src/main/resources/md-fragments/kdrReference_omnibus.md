/- The `reference` deployment flavor's copy from core (issue #1221): a reference deployment says what it is on its
   home page, on its sign-in page that an account here is temporary, on the profile page that the bar switches
   between a person's users too (issue #1330), and where a client or a workflow is made that a new workflow is seen
   by administrators only until it is opened up (issue #1384). The home page's Register card says, to a visitor who
   has not signed in, why to register and what the address is used for (issue #1652), and the Register page says the
   second again where the address is typed (issue #1698), with no business-account checkbox (issue #1699). A link to
   the site says what it is in its preview (issue #1688), and the band a public test deployment draws says it calmly
   (issue #1697). The sign-in line holds whichever `KDR_PUBLIC_SIGN_IN` the deployment chose: under `blocked`, the refusal itself says
   why. A deployment rewords any of it with `<flavor dir>/reference.md`, in this same format, naming only the keys it
   changes. -/

# !home
# @home
/- The title and intro are read by every visitor, signed in or not, and on the home page of every client that has not
   reworded them, the demos' included (issue #1686): they say what the product does before what this site is, and
   leave registering to the Register card. -/
# +title Try KotlinDynamicRuntime
# +intro
**KotlinDynamicRuntime** runs applications built around forms. People fill in forms, others review and approve them,
and the results end up in reports: a call for talks, a grant program, a vendor's onboarding. Each application's
forms, steps, people, and wording are configuration, which its administrators change while it runs, with no code to
write or deploy. It is open source.

This site runs it for you to try out. Its data is kept in a temporary database that is cleared from time to time, so
nothing you add here lasts, and nothing you do can break anything for long. To find your way around,
[start here](/docs/start-here.md).

/- The Register card's body (issue #1652), shown only to a visitor who has not signed in: what registering here is for,
   and what the address is used for, which once sat in the intro and so was read by the signed-in too. -/
# @registerCard
# +text
To try it, register a user of your own with your email address. Once you are signed in, your home page offers demo
clients to join, a conference's call for talks among them, as one of their people or as the person who runs them, and
a client of your own to build forms and workflows in. [The walk-through](/docs/walk-through.md) takes you through
both, a step at a time.

Your email address is used only to sign you in and for mail about your account here, such as a verification code.
It is not shared with anyone outside this site, and it is not used for marketing or to contact you about anything
else. Within the site, a client's administrators can see the email addresses of its users, so joining a demo shows
yours to the demo's administrators, as its join page explains.

If you registered here before, log in instead. An account lasts only until the site's data is cleared.

# @home

/- The shipped jump links with "Start here" before them (issue #1576), and before that the Register card (issue #1652),
   named here so it leads: a link to a section or a card not drawn for the caller is left out, so a signed-in visitor
   sees "Start here" first, and one who has not signed in sees Register and "Start here". -/
# +jumpLinks
- [Register](#home.card.register)
- [Start here](/docs/start-here.md)
- [Try a demo](#home.demos)
- [Your own client](#home.own-client)


# @publicHome
# +demosIntro
Each demo is a small organization already set up, with forms and workflows of its own. Join one as one of its people
or as the person who runs it, with a user of your own email, and join again in another role to see the same form from
the other side. Everyone who joins a demo shares it, and it is cleared with the rest of the site's data.
# +createClientNote
The two starter workflows are open to your client's people from the start. A workflow you make later from a template
is seen **only by your client's administrators** until you change who sees it: on **My client**, under **Workflows**,
choose **Visibility…** on its row, and add a label to try it with a few people, or choose **Everyone**.


/- What a link to the site shows (issue #1688): the tab's title, and the title and description a link preview draws. -/
# @site
# +title Try KotlinDynamicRuntime
# +description
Forms, reviews, and approvals, set up as configuration and changed while they run, with no code to deploy. Join a demo
as one of its people or as the person who runs it, or build forms and workflows of your own.


/- The band a public test deployment draws on every page (issue #1697), in words for a site that invites visitors. -/
# @testDeployment
# +band A demonstration site: what you add here is cleared from time to time.

# !profile
# @users
# +helpExtra You can also switch from your name in the bar at the top of every page.

# !clients
# @workflows
# +newWorkflowNote
Try the workflow before others meet it: put a label such as `pilot` on a few people on the **Users** page, and name it
here. When it is ready, choose **Visibility…** on its row and **Everyone** to make it generally available.

# !auth
# @login
# +intro
This site is for trying KotlinDynamicRuntime out. Anything you create here, your account included, is deleted whenever
its data is cleared.
/- Where a visitor types their address (issue #1698): the Register card's promise, said again at the form, since the
   card's button and the one under the home page's title both come straight here. -/
# @register
/- Left off (issue #1699): a business account changes only how its name is labeled, which nobody trying the site
   needs. -/
# +isEntityLabel
# +intro
Your email address is used only to sign you in and for mail about your account here, such as a verification code.
It is not shared with anyone outside this site, and it is not used for marketing or to contact you about anything
else. Within the site, a client's administrators can see the email addresses of its users, so joining a demo shows
yours to the demo's administrators.

An account lasts only until the site's data is cleared.
