[← The grants demo](README.md) · [Contents](README.md#the-pages)

# The site's name

The first words anyone reads are the name in the app bar, beside the brand mark, and the title on the browser tab. As
shipped, both say what the platform is called, which is right for a deployment and wrong for a client whose people know
it by its own name. A client that rewords nothing else rewords these, so that every page says whose site it is.

Here is the whole `siteWords` bundle:

<!-- kdr-doc: bundle=siteWords -->
```json
{
  "kdr:fragmentDef": [
    {
      "fileId": "home",
      "content": {
        "home": {
          "brand": "Riverbend Fund"
        },
        "site": {
          "title": "Riverbend Community Fund grants"
        }
      }
    }
  ]
}
```

Its one entry is a copy overlay of the `home` file, the copy the app bar and the home page read. It names two
namespaces of that file and one key in each, and every other key of the file keeps the platform's words.

## The words

| Where it is | Key | As shipped | In the demo |
| --- | --- | --- | --- |
| App bar: the wordmark beside the brand mark, on every page. | `home: home.brand` | KDR | Riverbend Fund |
| Browser tab: the title on every page's tab, for the client's signed-in people. | `home: site.title` | KotlinDynamicRuntime | Riverbend Community Fund grants |

**The wordmark is every page's.** The app bar draws it on every page, the home page included, so it is short: the name
the fund's people say.

**The tab's title is for the client's signed-in people.** A link to the site that someone pastes into a message is
previewed by whoever fetches it, before anyone is signed in, so a preview shows the deployment's title and description,
not a client's.

## What you see

Join the demo as an applicant (see [Try it](README.md#try-it)). The app bar says **Riverbend Fund** beside the brand
mark, and the browser tab says **Riverbend Community Fund grants**, on every page. Switch back to your own user from the
account menu, and both say what they said before.

Next: [The home page](home-page.md).
