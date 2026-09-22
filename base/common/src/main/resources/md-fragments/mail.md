/- The mails the auth flows send (issue #773): a verification code, an invitation, and the claim page's
   answers. A backend file -- never served, read by `MailCopy` for the client of the user the mail is about --
   so a client's overlay can reword a mail and name itself in it. Each mail is one namespace holding its
   `subject` and its `body`; the body is Markdown, sent as written for the text part and rendered for the HTML
   part. The `${...}` params are sanitized before substitution, so a value cannot inject a link or markup;
   a param holding a URL becomes a link in the HTML part on its own, and every other param is wrapped there
   so that Gmail does not turn an address into a mailto link.

   Every body spells out the address it is about, although it is the `to`: Gmail folds the repeated tail of a
   thread's mails behind an ellipsis, and a tester's plus-addressed variants of one inbox would otherwise all
   read alike. -/

/- Shared by every mail. `footer` closes each one; a client overlays it to sign as itself. `htmlStyle` is the
   inline style of the HTML part's body (inline only: mail clients strip stylesheets), where a client sets its
   colours. -/
# @common
# +footer This message was sent automatically. If you were not expecting it, you can ignore it.
# +htmlStyle font-family: -apple-system, Helvetica, Arial, sans-serif; font-size: 15px; line-height: 1.5; color: #222222;

/- The frontend's dev autofill and `AuthFlowTest` read the code out of "verification code is <code>." -- keep
   that phrase. -/
# @verifyCode
# +subject Your verification code
# +body Your verification code is ${code}. It expires in fifteen minutes.

# @verifyCodePassword
# +subject Your verification code
# +body Your verification code is ${code}. Enter it to set or change your password. It expires in fifteen minutes.

/- The link is the proof; the recipe beside it is for the login page's "claim" path, since a link is a
   courtesy some mail clients and scanners spoil. `persona` is the typed form the page wants ("admin",
   "member B"); `personaLabel` the displayed one ("Admin", "Member B"). -/
# @invitation
# +subject You have been invited
# +body
An account has been created for ${address} in '${client}' as ${personaLabel}. Open this link to accept it and sign in: ${url}

Or, from the login page, choose "Claim an account created for you" and enter your email address ${address} with client "${client}" and persona "${persona}"; a code will be sent to you there.

The link expires in seven days.


/- The claim page's answers (issue #751). `InvitationTest` reads the code out of "code for claiming the account
   ... is <code>." -- keep that phrase. The page always reports a code as sent; these say what actually matched,
   as specifically as the inbox's owner is entitled to. -/
# @claimCode
# +subject Claiming your account
# +body Your verification code for claiming the account ${address} in client "${client}" as ${personaLabel} is ${code}. Enter it on the page where you asked for it. It expires in fifteen minutes.

/- Nothing typed was even an id, so nothing typed is named. -/
# @claimNoMatch
# +subject Claiming your account
# +body We could not find an account for ${address} matching what was entered on the claim page. If you were invited, enter the client and persona exactly as the invitation gave them; otherwise nothing has been created.

# @claimNoMatchInClient
# +subject Claiming your account
# +body We could not find an account for ${address} in client "${client}" as ${personaLabel}. This address does have an account in that client; check the persona (and persona suffix) you were given.

# @claimNoMatchNamed
# +subject Claiming your account
# +body We could not find an account for ${address} in client "${client}" as ${personaLabel}. If you were invited, check the client and persona in the invitation; otherwise nothing has been created.
