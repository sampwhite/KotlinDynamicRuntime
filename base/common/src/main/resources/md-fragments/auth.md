/- Copy for the auth widget-group (issues #70, #81): the register/login flow and the account menu.
   Fetched via /st/<appId>/md/auth:<buildId>. The frontend resolves ${...} placeholders at render time;
   ${user.*} values are supplied from live state (the account name in the menu, the email in the code note). -/

# @menu
# +signedInAs Signed in as **${user.publicName}**
# +profile Profile
# +logout Log out
# +login Log in
# +register Register

# @login
# +title Log in
# +emailLabel Email address
# +passwordLabel Password
# +codeLabel Verification code
# +submit Log in
# +sendCode Email me a code to use for login
# +sendCodeSetPassword Email me a code and set a password
# +codeSent We emailed a verification code to `${user.email}`.
# +newPasswordLabel New password
# +newPasswordHelp You can use it to sign in from this browser next time.
# +failed Password login failed either because of an incorrect password or because you need to log in by verification code to activate password logins.

# @register
# +title Create your account
# +emailLabel Email address
# +sendCode Send verification code
# +codeSent A code was sent to `${user.email}`. Enter it below.
# +codeLabel Verification code
# +passwordLabel Password (optional)
# +passwordHelp Optional -- you can sign in with an emailed code, and add a password later from your profile.
# +finish Create account

# @verify
# +expiresNote The code expires in fifteen minutes.
# +resend Send a new code
