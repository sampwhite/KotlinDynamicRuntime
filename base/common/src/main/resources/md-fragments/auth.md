/- Copy for the auth widget-group (issue #70): the register/login flow and the account menu.
   Fetched via /st/<appId>/md/auth:<buildId>. The frontend resolves ${...} placeholders at render time;
   ${user.*} values are supplied from live state (e.g. the config endpoint's state.userInfo). -/

# @menu
# +signedInAs Signed in as ${user.publicName}
# +profile Profile
# +logout Log out
# +login Log in
# +register Register

# @login
# +title Log in
# +usernameLabel Username
# +passwordLabel Password
# +codeLabel Verification code
# +submit Log in
# +useCodeInstead Log in with a verification code instead
# +sendCode Email me a code
# +failed Password login failed either because of an incorrect password or because you need to log in by verification code to activate password logins.

# @register
# +title Create your account
# +emailLabel Email address
# +sendCode Send verification code
# +codeSent A code was sent to ${user.email}. Enter it below.
# +codeLabel Verification code
# +chooseUsername Choose a username
# +usernameHelp At least four characters: letters, digits, or underscores, starting with a letter.
# +finish Create account

# @verify
# +expiresNote The code expires in fifteen minutes.
# +resend Send a new code
