# Grants demo

The grants demo is a small community fund's grant program. People apply for a grant and say what it pays for, and the
fund's reviewers score each application. What sets the demo apart is its words. It rewords every piece of copy a client
is likely to reword, so its home page, its forms list, and its mails speak the fund's language rather than the
platform's. The fund is the Riverbend Community Fund, which is made up.

Each page that follows takes one theme. It says why a client would reword that part of the app, shows the JSON the demo
stores to do it, lists each piece of copy in a table (where you see it, the words the platform ships, and the demo's
words), and says how to see the result. The demo's workflows are only what its words need something to name. How a
client's workflows are written is the subject of the [conference demo](../demo-client/README.md).

## The pages

1. [The site's name](site-name.md): the wordmark in the app bar, and the title on the browser tab.
2. [The home page](home-page.md): its title, and the introduction under it.
3. [The words of the process](process-words.md): what the forms list, a workflow's page, a form, and a report call
   things.
4. [The mails](mails.md): the mails the fund's people are sent, and how every mail closes and looks.

## Try it

There are two ways to try the demo: join it on a public reference site, or run it on a test instance of your own.

### On a public reference site

A public reference site, such as [dynamicruntime.org](https://dynamicruntime.org), is a working instance of KDR that
anyone can sign in to and look around. The demo is already there, ready to join.

1. Choose **Register** from the menu, and create an account with your email address. (If you already have one, choose
   **Log in** instead.)
2. On the home page, under **Try a demo**, choose **Join…** on the **Grants demo** card.
3. Read **Before you join**, check the box that says you understand, and choose **Join as Applicant**.

The home page that opens is the fund's. The demo is shared with everyone who joins it, so you see the applications other
visitors make, and they see yours. You can also join as a reviewer, from the same card, to score them.

Neither role can change the demo's words, since a change would be every other visitor's too. To try rewording, make a
client of your own, from the same home page: its **Copy** panel, on its page under **Clients**, rewords any of the copy
these pages show, and previews each mail as it would be sent.

### On a test instance of your own

A test instance is one you run yourself, from a checkout of the
[KotlinDynamicRuntime repository](https://github.com/sampwhite/KotlinDynamicRuntime). The repository's
[README](https://github.com/sampwhite/KotlinDynamicRuntime/blob/main/README.md) explains how to set it up and start it.
Once it is running, open **Simulations** from the menu, and choose **Run** on **grants-demo**. It makes the demo, and an
applicant, a reviewer, and the fund's administrator to sign in as. The administrator can reword anything, as a
client's own administrator can.

## How a client keeps its words

A client changes copy one key at a time, in a copy overlay: an entry of its configuration that names a fragment file and
the keys it rewords, and leaves every other key as the platform ships it. The
[copy reference](../reference/copy.md#a-clients-copy-overlay) describes it. The demo keeps its words in four bundles,
one per theme, so each page can show one whole:

| Bundle | Page | What it rewords |
| --- | --- | --- |
| `siteWords` | [The site's name](site-name.md) | The app bar's wordmark, and the browser tab's title. |
| `homeWords` | [The home page](home-page.md) | The home page's title and introduction. |
| `formsWords` | [The words of the process](process-words.md) | The forms list, a workflow's page, a form's lists, and a report's review column. |
| `mailWords` | [The mails](mails.md) | Each mail the fund's people are sent, and what every mail ends with. |

Which copy a client is likely to reword, and where you see each piece, is written beside the copy itself, in the
fragment file that ships it ([Where a key is shown](../reference/copy.md#where-a-key-is-shown)). The tables in these
pages quote those notes, and the demo rewords exactly the copy that has one. Everything else (a sign-in page's words,
say, which a visitor reads before anyone knows their client) stays the platform's.

Next: [The site's name](site-name.md).
