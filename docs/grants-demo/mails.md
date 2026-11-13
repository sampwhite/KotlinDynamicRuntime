[← The words of the process](process-words.md) · [Contents](README.md#the-pages)

# The mails

A mail is read away from the site, in an inbox full of other mail, so it has to say who it is from. As shipped, the
mails the platform sends say what happened and nothing of whose site it was. Each mail is worded for the client of the
person it is about, so a client that rewords them signs every mail its people are sent: their sign-in codes, their
invitations, and the reminders an administrator sends. One mail stays the platform's: the code sent to someone
registering, who is not yet anyone's.

Here is the whole `mailWords` bundle:

<!-- kdr-doc: bundle=mailWords -->
```json
{
  "kdr:fragmentDef": [
    {
      "fileId": "mail",
      "content": {
        "common": {
          "fromName": "Riverbend Community Fund",
          "footer": "From the grants team at the Riverbend Community Fund.",
          "sentFrom": "Sent from the fund's grant site, ${siteHost}.",
          "htmlStyle": "font-family: Georgia, serif; font-size: 16px; line-height: 1.6; color: #1f3b2d;"
        },
        "verifyCode": {
          "subject": "Your Riverbend sign-in code",
          "body": "Your verification code is [${code}]{.code}.\n\nEnter it on the Riverbend grant site to sign in. It expires in fifteen minutes."
        },
        "verifyCodePassword": {
          "subject": "Your Riverbend password code",
          "body": "Your verification code is [${code}]{.code}.\n\nEnter it to set or change your password for the Riverbend grant site. It expires in fifteen minutes."
        },
        "invitation": {
          "subject": "Your account with the Riverbend Community Fund",
          "body": "The Riverbend Community Fund has made you an account on its grant site, for [${address}]{.value}, as [${personaLabel}]{.value}.\n\nAccept it and sign in here: ${url}\n\nIf the link does not work for you, go to the login page, choose \"Claim an account created for you\", and enter:\n\n- Email: [${address}]{.value}\n- Client ID: [${client}]{.value}\n- Persona: [${personaLabel}]{.value}\n\nA code will then be sent to [${address}]{.value}.\n\nThe link expires in seven days."
        },
        "signInReminder": {
          "subject": "Signing in to the Riverbend grant site",
          "body": "You have an account for [${address}]{.value} on the Riverbend Community Fund's grant site, as [${personaLabel}]{.value}.\n\nSign in here: ${url}\n\nUse this address, with your password if you set one, or a code sent to it."
        },
        "claimCode": {
          "subject": "Claiming your Riverbend account",
          "body": "Your verification code for claiming the account [${address}]{.value} at the Riverbend Community Fund as [${personaLabel}]{.value} is [${code}]{.code}.\n\nEnter it on the page where you asked for it. It expires in fifteen minutes."
        },
        "claimNoMatchInClient": {
          "subject": "Claiming your Riverbend account",
          "body": "We could not find a Riverbend account matching what was entered on the claim page:\n\n- Email: [${address}]{.value}\n- Client ID: [${client}]{.value}\n- Persona: [${personaLabel}]{.value}\n\nThis address does have an account with the fund. Check the persona (and persona suffix) against what your invitation gave you."
        }
      }
    }
  ]
}
```

All of it is in the `mail` file, which no page shows: the server reads it as it sends a mail. Each mail is one
namespace, with its `subject` and its `body`, and the `common` namespace holds what every mail ends with and how it
looks.

## The words

| Where it is | Key | As shipped | In the demo |
| --- | --- | --- | --- |
| Mail: the name every mail is from, shown in the inbox list and above the subject. | `mail: common.fromName` | KotlinDynamicRuntime | Riverbend Community Fund |
| Mail: the line closing every mail. | `mail: common.footer` | This message was sent automatically. If you were not expecting it, you can ignore it. | From the grants team at the Riverbend Community Fund. |
| Mail: the line after the closing one, naming the site the mail came from. | `mail: common.sentFrom` | `Sent from ${siteHost}.` | `Sent from the fund's grant site, ${siteHost}.` |
| Mail: the style of every mail as it is drawn, its font, size, and colors. | `mail: common.htmlStyle` | font-family: -apple-system, Helvetica, Arial, sans-serif; font-size: 15px; line-height: 1.5; color:… | font-family: Georgia, serif; font-size: 16px; line-height: 1.6; color: #1f3b2d; |
| Mail: the subject of the code sent to one of the client's people signing in by code. A registration's code uses the same key in the deployment's words, since no client is known yet. | `mail: verifyCode.subject` | Your verification code | Your Riverbend sign-in code |
| Mail: the body of the code sent to one of the client's people signing in by code. A registration's code uses the same key in the deployment's words, since no client is known yet. | `mail: verifyCode.body` | `Your verification code is [${code}]{.code}. Enter it where you asked for it. It expires in fifteen…` | `Your verification code is [${code}]{.code}. Enter it on the Riverbend grant site to sign in.…` |
| Mail: the subject of the code sent to one of the client's people setting or changing a password. | `mail: verifyCodePassword.subject` | Your verification code | Your Riverbend password code |
| Mail: the body of the code sent to one of the client's people setting or changing a password. | `mail: verifyCodePassword.body` | `Your verification code is [${code}]{.code}. Enter it to set or change your password. It expires in…` | `Your verification code is [${code}]{.code}. Enter it to set or change your password…` |
| Mail: the subject of the invitation sent to someone an administrator adds to the client. | `mail: invitation.subject` | You have been invited | Your account with the Riverbend Community Fund |
| Mail: the body of the invitation sent to someone an administrator adds to the client. | `mail: invitation.body` | `An account has been created for [${address}]{.value} at [${clientName}]{.value}, as…` | The Riverbend Community Fund has made you an account on its grant site… |
| Mail: the subject of the reminder of how to sign in that an administrator sends one of the client's people. | `mail: signInReminder.subject` | `Your account at ${clientName}` | Signing in to the Riverbend grant site |
| Mail: the body of the reminder of how to sign in that an administrator sends one of the client's people. | `mail: signInReminder.body` | `You have an account for [${address}]{.value} at [${clientName}]{.value}, as…` | `You have an account for [${address}]{.value} on the Riverbend Community Fund's grant site…` |
| Mail: the subject of the code sent to someone claiming an account the client made for them. | `mail: claimCode.subject` | Claiming your account | Claiming your Riverbend account |
| Mail: the body of the code sent to someone claiming an account the client made for them. | `mail: claimCode.body` | `Your verification code for claiming the account [${address}]{.value} at [${clientName}]{.value} as…` | `Your verification code for claiming the account [${address}]{.value} at the Riverbend Community…` |
| Mail: the subject of the claim page's answer to one of the client's people, when the persona typed did not match. | `mail: claimNoMatchInClient.subject` | Claiming your account | Claiming your Riverbend account |
| Mail: the body of the claim page's answer to one of the client's people, when the persona typed did not match. | `mail: claimNoMatchInClient.body` | We could not find an account matching what was entered on the claim page: - Email:… | We could not find a Riverbend account matching what was entered on the claim page… |

**A mail's values are filled in as it is sent.** `${code}`, `${address}`, `${url}` and the others are the
mail's own, and each mail's shipped body uses the ones it has. Reworded, a body keeps the values a reader needs: a code
mail its code, an invitation its link and what to type if the link fails. The
[copy reference](../reference/copy.md#roles) explains `{.code}` and `{.value}`, which set a code or an address apart so
a reader can copy it.

**The style is the HTML part's.** A mail goes out as text and as HTML, from the one body. `htmlStyle` is the inline
style of the HTML part, where a client sets its font and colors. Inline only, since mail clients strip style sheets.

**Who it is from** is `fromName`, the name a mail app shows in the inbox list and above the subject, beside the
deployment's sending address. It is plain words, and a client may name itself with `${clientName}`. A deployment can
word it for every client: a reference deployment's mails are from "Gedra". The code sent to someone registering has no
client yet, so it is always from the deployment's name.

**Every mail closes the same way**: the body, then `footer`, then `sentFrom`. A client that wants no line naming the
site makes `sentFrom` empty.

## What you see

Sign in by code as one of the fund's people, and the mail that arrives is the fund's. It is from **Riverbend Community
Fund**, its subject is **Your Riverbend sign-in code**, it closes with the grants team's line and names the fund's grant
site, and its HTML part is set in a serif.

- **On a public reference site,** your demo user is one of the fund's people, but your address signs in as whichever of
  your users is chosen for it. On your **Profile** page, choose **Use for next sign-in** on your Grants demo user, then
  log out, and log in with **Email me a code to use for login**.
- **On a test instance of your own,** log out, and log in as `applicant@grantsdemo.example` the same way. As the demo's
  administrator, invite someone from the **Users** page, and the invitation is the fund's too.

A code for registering is still the platform's, since whoever registers is not yet one of the fund's people.

**A client's administrator can look at a mail without sending one.** On the client's page under **Clients**, its
**Copy** panel shows the `mail` file with **Preview this mail** beside each mail's name. The preview is drawn by the
code that sends the mail, with a made-up address and code: its subject, its HTML part as a mail client draws it, and its
text part. For a client with a sandbox, where a change to its copy is saved as a draft, the preview shows the draft.
