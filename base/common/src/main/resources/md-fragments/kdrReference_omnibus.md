/- The `reference` deployment flavor's copy from core (issue #1221): a reference deployment says what it is on its
   home page, on its sign-in page that an account here is temporary, on the profile page that the bar switches
   between a person's users too (issue #1330), and where a client or a workflow is made that a new workflow is seen
   by administrators only until it is opened up (issue #1384). The home page's Register card says, to a visitor who
   has not signed in, why to register and what the address is used for (issue #1652). The sign-in line holds whichever
   `KDR_PUBLIC_SIGN_IN` the deployment chose: under `blocked`, the refusal itself says why. A deployment rewords any of it with
   `<flavor dir>/reference.md`, in this same format, naming only the keys it changes. -/

# !home
# @home
# +title A reference deployment
# +intro
This is a **reference deployment** of the KotlinDynamicRuntime: a working instance you can look around, built
from the same code and configuration as any other. Its data is kept in a temporary database that is cleared from
time to time, so anything you add here will not last.

To read how it is put together, [start here](/docs/start-here.md): it is first under **Documents**, in the menu's
Reference section, and says what to read for what you want to do, from trying a demo to having an AI assistant
configure a client. Its walk-through starts from a user of your own, so it is most useful once you are signed in.

/- The Register card's body (issue #1652), shown only to a visitor who has not signed in: what registering here is for,
   and what the address is used for, which once sat in the intro and so was read by the signed-in too. -/
# @registerCard
# +text
The suggested next step is to register a user of your own with your email address. Once you are signed in, the home
page lists the demo clients you can join, the conference demo among them, so you can try each one as an administrator
or as one of its people, and [the walk-through](/docs/walk-through.md) takes you through them a step at a time.

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
This is a teaching site: join a demo client to see one from the inside, as an administrator or as one of its people,
with a user of your own email. Each demo is shared with everyone who joins it, and cleared with the rest of the site's
data.
# +createClientNote
The two starter workflows are open to your client's people from the start. A workflow you make later from a template
is seen **only by your client's administrators** until you change who sees it: on **My client**, under **Workflows**,
choose **Visibility…** on its row, and add a label to try it with a few people, or choose **Everyone**.

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
This is a reference deployment for trying KotlinDynamicRuntime out. Anything you create here, your account included,
is deleted whenever its data is cleared.
