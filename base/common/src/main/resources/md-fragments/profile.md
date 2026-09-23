/- Copy for the profile widget-group (issue #70): the login-required profile page.
   Fetched via /st/<appId>/md/profile:<buildId>. ${user.*} values are supplied from live state. -/

# @profile
# +title Your profile
# +signedInAs Signed in as **${user.publicName}**
# +emailLine Email: `${user.email}`
# +logout Log out

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
