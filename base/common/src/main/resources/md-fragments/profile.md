/- Copy for the profile widget-group (issue #70): the login-required profile page.
   Fetched via /st/<appId>/md/profile:<buildId>. ${user.*} values are supplied from live state. -/

/- The page's own words (issue #1479). `structureLabel` is what a screen reader calls the line under the title: the
   address, the person's users, and the access of the one acted as. The name is a panel of its own, saved by its own
   Save; what you are called is not a credential, so it needs no emailed code. -/
# @profile
# +title Your profile
# +structureLabel How your account is put together
# +nameTitle Your name
# +nameHelp The name you are shown under. It need not be unique, and is not how you sign in. Leave it empty to be shown by your login email instead.
# +nameLabel Name
# +businessNameLabel Business name
# +nameSave Save name
# +nameSaved Your name was saved.
# +nameCleared Your name was cleared.
# +nameFailed Couldn't save your name.
# +keysTitle API keys
# +logout Log out
# +logoutFailed Couldn't log out.

/- The account panel (issue #1479): what the page and the shell already know of the signed-in user, read-only. Each
   label's value is supplied by the page, made of the words below where it is not data: `personal` or `business` for
   the account type; `everyClient` after an administrator's or operator's access when they hold `allClients`;
   `notYet` for an address nobody has proven (issue #1480); and `siteZone`, followed by the zone's label, when the
   client names no time zone. The user's own moments -- when the address was proven, when the user was registered,
   and the sign-in before this one (a switch between users is none) -- are absolute, each labeled with its zone. `canDoTitle` heads what the user may
   do here, one line per thing, chosen from the `can*` keys: every client, else their own client (`canAdministerOrg`
   for one confined to an organization), else their own address's users (an administrator in `public`); an editor's,
   as far; an operator's; opening the sandbox; and, for a user who administers and edits no client, their own forms. -/
# @account
# +title Your account
# +signedInAs Signed in as
# +email Email
# +emailProven Email proven
# +client Client
# +persona Persona
# +access Access
# +org Organization
# +accountType Account type
# +registered Registered
# +previousSignIn Previous sign-in
# +timeZone Time zone
# +personal Personal
# +business Business
# +everyClient across every client
# +siteZone This site's default
# +notYet Not yet
# +canDoTitle What you can do here
# +canAllClients Administer every client: its users, its configuration, and its forms.
# +canAdministerClient Administer your client: its users, its configuration, and every form in it.
# +canAdministerOrg Administer your client's configuration, and the users and forms of your organization.
# +canOwnUsers Manage the users of your own address, and nobody else's.
# +canEditClient See the users and forms of your client, and edit its forms.
# +canEditOrg See the users and forms of your organization, and edit its forms.
# +canOperateSite See how this site is running: its settings, boot checks, caches, and file store.
# +canReadFacts Look up what your client's configuration facts mean.
# +canOpenSandbox Open your client's sandbox, to try a change before it is published.
# +canOwnForms Work with your own forms.

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
![One email address, several users](img:kdr:emailToUsers){.float-left width=120}
Your email address can be bound to more than one user, each with its own data and profile. You sign in once — with
your email address, and your password if you set one — but that sign-in can reach **several users**. Each is a
separate account:
[]{.clear}

- **In a client.** A client is an organization's own space in the app, with its own forms and workflows.
- **With a persona**, such as Member or Admin, which names which of your users it is there. What a user may do is
  its access, which an administrator sets and may change later; when the two differ, the persona says so.
- **Owning its own content.** Forms belong to the user that made them. One of your users sees another's forms only
  if its access would let it see anybody's.
- **With its own profile.** The name on this page belongs to the user you are acting as now, so each can be named
  differently. Your email address and password belong to your sign-in, so they are the same for all of them.

Switching moves you from one to another without signing in again.


# +personaColumn Persona
# +statusColumn Status
# +clientColumn Client
# +nameColumn Name
# +activeMark Active now
# +nextSignInMark Next sign-in
# +switch Switch to this user
# +useForNextSignIn Use for next sign-in
# +nextSignInSet Your next sign-in now takes you to that user.

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

/- The Sign-in panel (issue #1479): the password's status, the emailed code that a change needs, and its removal, which
   asks first (`removeQuestion`). `remove` is the danger button that asks, drawn with an ellipsis; `removeConfirm` is
   the one that removes. -/
# @password
# +title Sign-in
# +hasPassword You have a password set. You can change it or remove it.
# +noPassword You have not set a password. You currently sign in with a verification code.
# +newPasswordLabel New password
/- The button says what the code is *for*: on its own, "email me a code" gives no hint that it is the way to
   set a password. Two variants, as the password is set or changed. -/
# +sendCodeSet Email me a code so I can set a password
# +sendCodeChange Email me a code so I can change my password
# +codeSent We emailed a verification code to `${user.email}`.
# +codeLabel Verification code
# +save Save password
# +saveFailed Couldn't save your password.
# +cancel Cancel
# +remove Remove password
# +removeQuestion Remove your password? You can still sign in with a verification code, and set a password again later.
# +removeConfirm Remove password
# +keep Keep password
# +saved Your password was saved. You can use it to sign in from this browser next time.
# +removedNote Your password was removed. You can still sign in with a verification code.
