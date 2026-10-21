/- Copy for the profile widget-group (issue #70): the login-required profile page.
   Fetched via /st/<appId>/md/profile:<buildId>. ${user.*} values are supplied from live state. -/

# @profile
# +title Your profile
# +signedInAs Signed in as **${user.publicName}**
# +emailLine Email: `${user.email}`
# +clientLine Client: **${user.clientName}**
# +logout Log out

/- The person's users (issue #1330). `help` is the status, always shown: which user the session is acting as, and which
   one a sign-in lands on -- the chosen default when it can be used, else the one last acted as. ${users.active} and
   ${users.signsInAs} are supplied by the page, each as "Member B in Acme". `helpExtra` is empty as shipped; a
   deployment flavor's overlay adds to it (the reference flavor says the bar can switch too). `whatIsThis` is the
   concept behind it, opened from the heading's "What is this?" link; empty, the link is not offered. -/
# @users
# +title Your users
# +help
You are acting as **${users.active}**; switch to another below and you stay on this page. Next sign-in takes you to
**${users.signsInAs}**: the user you chose with **Use for next sign-in**, or else the one you last acted as.

${@t("users.helpExtra")}


# +helpExtra
# +whatIsThis
You sign in once — with your email address, and your password if you set one — but that sign-in can reach
**several users**. Each is a separate account:

- **In a client.** A client is an organization's own space in the app, with its own forms and workflows.
- **With a persona**, such as Member or Admin, which decides what that user may do there.
- **Owning its own content.** Forms belong to the user that made them. One of your users sees another's forms only
  if its access would let it see anybody's.
- **With its own profile.** The name on this page belongs to the user you are acting as now, so each can be named
  differently. Your email address and password belong to your sign-in, so they are the same for all of them.

Switching moves you from one to another without signing in again.


# +personaColumn Persona
# +clientColumn Client
# +nameColumn Name
# +activeMark Active now
# +nextSignInMark Next sign-in
# +switch Switch to this user
# +useForNextSignIn Use for next sign-in
# +nextSignInSet Your next sign-in now takes you to that user.

/- The words of a "What is this?" link and of closing what it opened (issue #1330), for every one on the page. -/
# @whatIsThis
# +link What is this?
# +hide Hide

/- The public placeholder (issue #752): a person may remove their public users, down to nothing -- they
   registered themselves, so they can register again. `helpOnly` is for their last user. -/
# @placeholder
# +title Your public account
# +help You registered before being placed anywhere, so you have an account in the public placeholder client. You can remove it. This is permanent, and your other accounts are not affected.
# +helpOnly This is your only account. Removing it permanently deletes your registration: you are signed out, and your email address is free to register again.
# +remove Remove
# +confirm Permanently remove
# +cancel Cancel
# +removedNote The public account was removed.

# @password
# +hasPassword You have a password set. You can change it or remove it.
# +noPassword You have not set a password. You currently sign in with a verification code.
# +setTitle Set a password
# +changeTitle Change your password
# +newPasswordLabel New password
/- The button says what the code is *for*: on its own, "email me a code" gives no hint that it is the way to
   set a password. Two variants, matching setTitle/changeTitle. -/
# +sendCodeSet Email me a code so I can set a password
# +sendCodeChange Email me a code so I can change my password
# +codeSent We emailed a verification code to `${user.email}`.
# +codeLabel Verification code
# +save Save password
# +cancel Cancel
# +remove Remove password
# +saved Your password was saved. You can use it to sign in from this browser next time.
# +removedNote Your password was removed. You can still sign in with a verification code.
