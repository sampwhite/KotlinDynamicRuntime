/- The `reference` deployment flavor's copy from core (issue #1221): a reference deployment says what it is on its
   home page, and why sign-in is limited on its sign-in page. A deployment rewords any of it with
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

# !auth
# @login
# +intro
Sign-in is limited here: this deployment is a public reference, not a service, so it does not take ordinary
sign-ins or registrations. What it shows is described under **Documents**.
