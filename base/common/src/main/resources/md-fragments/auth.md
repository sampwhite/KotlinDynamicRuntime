/- Copy for the auth widget-group (issues #70, #81): the register/login flow and the account menu.
   Fetched via /st/<appId>/md/auth:<buildId>. The frontend resolves ${...} placeholders at render time;
   ${user.*} values are supplied from live state (the account name in the menu, the email in the code note). -/

# @menu
# +signedInAs Signed in as ${user.publicName}
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
# +failed Password login failed either because of an incorrect password or because you need to log in by verification code to activate password logins.

# @register
# +title Create your account
# +emailLabel Email address
# +sendCode Send verification code
# +codeSent A code was sent to ${user.email}. Enter it below.
# +codeLabel Verification code
# +finish Create account

# @verify
# +expiresNote The code expires in fifteen minutes.
# +resend Send a new code
