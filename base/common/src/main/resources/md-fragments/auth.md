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
/- Hedged on purpose (issues #275, #565): the backend answers an unknown address exactly as a known one so that
   asking for a code is not a membership oracle, so this cannot say a code *was* sent. Vague by design: it says
   to check the address, never that the address has no account. -/
# +codeSent If `${user.email}` has an account, a code is on its way. Check that the address is correct.
# +newPasswordLabel New password
# +newPasswordHelp You can use it to sign in from this browser next time.
# +orDivider or

# @register
# +title Create your account
# +emailLabel Email address
/- For an allClients administrator only (issue #751): the client, persona and personId the new user takes. -/
# +clientLabel Client
# +personaLabel Persona
# +personIdLabel Person id
# +provisionHelp As an administrator across clients, you may place the new account: which client, what kind of user, and a short id for a further user of the same kind. Leave them alone for an ordinary registration.
# +sendCode Send verification code
# +codeSent A code was sent to `${user.email}`. Enter it below.
# +codeLabel Verification code
# +passwordLabel Password (optional)
# +passwordHelp Optional -- you can sign in with an emailed code, and add a password later from your profile.
# +finish Create account
# +orDivider or

# @verify
# +expiresNote The code expires in fifteen minutes.

/- The invitation page (issue #751): reached from a mailed link. Nothing happens until the person accepts,
   so a mail scanner that opens the link accepts nothing on their behalf. -/
# @invite
# +title You have been invited
# +summary An account has been created for **${invite.email}** in **${invite.client}** as **${invite.persona}**.
# +explain Accepting proves you can read mail at that address, makes the account yours, and signs you in.
# +accept Accept and sign in
# +accepted You are signed in. The account is now yours; it appears in the account menu when you have others.
# +invalid This invitation link is not valid, or has expired.
/- The way back as well as a resend (issue #565): it unlocks the address. -/
# +resend Change the address or send a new code

/- Error messages (issue #108). The backend renders these server-side from KdrException's KdrMsg and marks the
   response errorFromFragment=true. Markdown is allowed here (unlike the earlier note): it is safe because the
   substituted ${...} params are run through sanitizeForDisplay, so a param cannot inject a link. The frontend
   still shows these as plain text today, so Markdown would render literally until it renders them as Markdown
   (a later frontend change) – author with that in mind. Keys match AERR. -/
# @error
# +codeIncorrect The verification code is incorrect.
# +tokenExpired The sign-in form has expired. Please request a new code and try again.
# +emailInvalid That does not look like an email address. Please check it and try again.
# +loginFailed Password login failed, either because the password was incorrect or because you need to log in by verification code to activate password logins.
# +tooManyVerifyAttempts Too many verification attempts. Please request a new code and try again.
# +tooManyVerifyRequests Too many verification requests. Please wait a while before requesting another code.
# +tooManyLoginAttempts Too many failed login attempts. Please wait and try again, or log in by verification code.
# +googleNotConfigured Google sign-in is not available on this deployment.
# +googleTokenInvalid Google sign-in could not be verified. Please try again.
# +googleEmailUnverified Google has not verified the email address on that account, so it cannot be used to sign in here.
# +emailNotAvailable The email *${email}* is not available for creating a new account.
# +userKeyTaken A user for ${email} already exists in client ${client} with persona ${persona}${personIdNote}.
# +invitationInvalid This invitation link is not valid, or has expired. Ask the person who invited you to send a new one.
# +invitationUsed This invitation has already been accepted. Log in with the address it was sent to.
