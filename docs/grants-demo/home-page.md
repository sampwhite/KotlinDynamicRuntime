[← The site's name](site-name.md) · [Contents](README.md#the-pages)

# The home page

The home page is where the client's people land after signing in, and its words as shipped describe the platform: a
welcome, and how the page is put together. A client rewords them to say what the site is for and what to do first. The
demo's introduction says what the fund does, and where an applicant and a reviewer start.

Here is the whole `homeWords` bundle:

<!-- kdr-doc: bundle=homeWords -->
```json
{
  "kdr:fragmentDef": [
    {
      "fileId": "home",
      "content": {
        "home": {
          "title": "Riverbend Community Fund",
          "intro": "The **Riverbend Community Fund** gives small grants to neighborhood projects: a garden, a mural, a repair café. Ask for up to $5,000, say what it pays for, and the fund's reviewers score your application.\n\nTo apply, open your forms from the menu and choose **Create a form**. Each application then shows its next step to **Begin**. Reviewers find the applications waiting for them under **To score**."
        }
      }
    }
  ]
}
```

## The words

| Where it is | Key | As shipped | In the demo |
| --- | --- | --- | --- |
| Home: the page's title. | `home: home.title` | Welcome | Riverbend Community Fund |
| Home: the paragraphs under the title. | `home: home.intro` | This is the **KotlinDynamicRuntime** runtime… | The **Riverbend Community Fund** gives small… |

**The introduction is Markdown**, as all copy is, so it can hold emphasis, links, and lists, and its paragraphs are
separated by a blank line (`\n\n` in the JSON). The [copy reference](../reference/copy.md#the-markdown-it-reads) lists
what it reads.

**It names the words of the other pages.** The introduction tells an applicant to look for **Begin** and a reviewer for
**To score**, which are the demo's words for the forms list's links and column, from the
[next page](process-words.md). Words that point at other words are changed together, or they stop agreeing.

## What you see

Join the demo as an applicant (see [Try it](README.md#try-it)). The home page that opens is titled **Riverbend Community
Fund**, with the fund's introduction under it.

Next: [The words of the process](process-words.md).
