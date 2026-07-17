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

/- Error messages (issue #108). The backend renders these server-side from KdrException's KdrMsg -- they are
   shown to the user as plain text (NOT markdown-rendered), so no backticks/emphasis here. Keys match AERR. -/
# @error
# +codeIncorrect The verification code is incorrect.
# +tokenExpired The sign-in form has expired. Please request a new code and try again.
# +emailNoAt An email address must contain an '@'.
# +loginFailed Password login failed, either because the password was incorrect or because you need to log in by verification code to activate password logins.
# +tooManyVerifyAttempts Too many verification attempts. Please request a new code and try again.
# +tooManyVerifyRequests Too many verification requests. Please wait a while before requesting another code.
# +tooManyLoginAttempts Too many failed login attempts. Please wait and try again, or log in by verification code.
# +noAccount No account was found for ${loginId}.
# +emailNotAvailable The email ${email} is not available for creating a new account.
