/- The `reference` deployment flavor's copy from core (issue #1221): a reference deployment says what it is on its
   home page, on its sign-in page that an account here is temporary, and on the profile page that the bar
   switches between a person's users too (issue #1330). The sign-in line holds whichever
   `KDR_PUBLIC_SIGN_IN` the deployment chose: under `blocked`, the refusal itself says why. A deployment rewords any of it with
   `<flavor dir>/reference.md`, in this same format, naming only the keys it changes. -/

# !home
# @home
# +title A reference deployment
# +intro
This is a **reference deployment** of the KotlinDynamicRuntime: a working instance you can look around, built
from the same code and configuration as any other. Its data is kept in a temporary database that is cleared from
time to time, so anything you add here will not last.

Open **Documents** from the menu to read how it is put together, starting with the conference demo -- a client
built from configuration alone, explained piece by piece.

# @publicHome
# +demosIntro
This is a teaching site: join a demo client to see one from the inside, as an administrator or as one of its people,
with a user of your own email. Each demo is shared with everyone who joins it, and cleared with the rest of the site's
data.

# !profile
# @users
# +helpExtra You can also switch from your name in the bar at the top of every page.

# !auth
# @login
# +intro
This is a reference deployment for trying KotlinDynamicRuntime out. Anything you create here, your account included,
is deleted whenever its data is cleared.
