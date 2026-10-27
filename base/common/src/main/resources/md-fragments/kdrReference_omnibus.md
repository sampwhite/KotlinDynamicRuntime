/- The `reference` deployment flavor's copy from core (issue #1221): a reference deployment says what it is on its
   home page, on its sign-in page that an account here is temporary, on the profile page that the bar switches
   between a person's users too (issue #1330), and where a client or a workflow is made that a new workflow is seen
   by administrators only until it is opened up (issue #1384). The sign-in line holds whichever
   `KDR_PUBLIC_SIGN_IN` the deployment chose: under `blocked`, the refusal itself says why. A deployment rewords any of it with
   `<flavor dir>/reference.md`, in this same format, naming only the keys it changes. -/

# !home
# @home
# +title A reference deployment
# +intro
This is a **reference deployment** of the KotlinDynamicRuntime: a working instance you can look around, built
from the same code and configuration as any other. Its data is kept in a temporary database that is cleared from
time to time, so anything you add here will not last.

The suggested next step is to register a user of your own: choose **Register** from the menu, and give your email
address. Once you are signed in, this page lists the demo clients you can join, the conference demo among them, so
you can try each one as an administrator or as one of its people.

Your email address is used only to sign you in and for mail about your account here, such as a verification code.
It is not shared with anyone outside this site, and it is not used for marketing or to contact you about anything
else. Within the site, a client's administrators can see the email addresses of its users, so joining a demo shows
yours to the demo's administrators, as its join page explains.

Open **Documents** from the menu to read how it is put together, starting with the conference demo -- a client
built from configuration alone, explained piece by piece.

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
