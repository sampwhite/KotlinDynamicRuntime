/- Copy for the profile widget-group (issue #70): the login-required profile page.
   Fetched via /st/<appId>/md/profile:<buildId>. ${user.*} values are supplied from live state. -/

# @profile
# +title Your profile
# +signedInAs Signed in as **${user.publicName}**
# +emailLine Email: `${user.email}`
# +clientLine Client: **${user.clientName}**
# +logout Log out

/- The person's users (issue #1330): what they are, which one the session is acting as, and which one a sign-in lands
   on -- the chosen default when it can be used, else the one last acted as. ${users.active} and ${users.signsInAs}
   are supplied by the page, each as "Member B in Acme". `helpExtra` is empty as shipped; a deployment flavor's overlay
   adds to it (the reference flavor says the bar can switch too). -/
# @users
# +title Your users
# +help
Your sign-in reaches more than one user: each is you in a client, under a persona, with its own forms and access.
You are acting as **${users.active}**; switch to another below and you stay on this page.

Signing in again takes you to **${users.signsInAs}**: the user you choose with **Sign in as this one**, or else the
one you last acted as.

${@t("users.helpExtra")}


# +helpExtra
# +userColumn User
# +clientColumn Client
# +activeMark Active now
# +signsInAsMark Signs in as
# +switch Switch to this user
# +makeDefault Sign in as this one
# +defaultSet Signing in now takes you to that user.

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
