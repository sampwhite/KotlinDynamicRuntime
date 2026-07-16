/- Copy for the profile widget-group (issue #70): the login-required profile page.
   Fetched via /st/<appId>/md/profile:<buildId>. ${user.*} values are supplied from live state. -/

# @profile
# +title Your profile
# +signedInAs Signed in as **${user.publicName}**
# +logout Log out

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
