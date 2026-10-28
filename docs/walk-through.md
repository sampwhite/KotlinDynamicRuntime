<!-- The walk-through for a person signed in to a reference deployment (issue #1429), linked from their home page.
     Its tables are what the reader types, and PublicWalkThroughTest (base/kdn) enters each one, through the same
     endpoints the pages use, so a label or a choice the demo renames fails the build. A table is marked on the line
     before it: in part 1, `kdr-walk: workflow=<id>` for a form of that workflow and `kdr-walk: name` for a name on the
     profile; in part 2, a name for each step (`kdr-walk: copy`, `kdr-walk: fields`, and so on), which the test lists.
     `*your first name*` and `*an address of yours*` are placeholders the test fills in. -->

# A walk-through

This page takes you through the site one step at a time. Each step names what to choose and what to type, so you
can follow along without having to decide anything. The links go to the pages you need, here on this site.

Everything you make here is kept in a temporary database, which is cleared from time to time, so nothing you do can
break anything for long.

## Part 1: The conference demo

The conference demo is a small conference's call for talks. Speakers submit the talks they want to give, and a
committee reviews each one and accepts it for the program. You play both parts: first a speaker, then a member of the
committee who reviews your own talk.

The demo is shared with everyone who joins it. Other visitors see the talks you add, and you see theirs.

### Join as a speaker

1. Open the [Join page for the conference demo](/wa/join/confdemo). (From your home page, it is **Join…** on the
   **Conference demo** card, under **Try a demo**.)
2. Read **Before you join**, and check the box that says you understand.
3. Choose **Join as Speaker**.

Joining gives you a new user of your own, as a speaker in the conference demo, and switches you to it. The bar at the
top of every page shows which user you are acting as, and the client it is in.

### Submit a talk

Open **My forms** from the menu, and choose **Create a form**. The form that opens is **Submit a talk**, and it asks
for one thing:

<!-- kdr-walk: workflow=createTalk -->
| Field | What to type |
| --- | --- |
| Name | Coroutines for *your first name* |

Choose **Create the talk**. The talk appears in your forms list, with the status **Needs Info**: the conference needs
to know more before the committee looks at it.

### Answer the survey

Choose **Needs Info** on your talk's row. The form that opens is **About your talk**:

<!-- kdr-walk: workflow=talkSurvey -->
| Field | What to choose |
| --- | --- |
| Track | Kotlin |
| Is this your first conference talk? | Yes |

Choose **Save**. Back in the forms list, the talk's status is **Valid**, and the **Workflows** column now offers
**Committee review**, which waits for a complete survey.

### Write the abstract

A talk can be in several workflows at once, each working on its own part of the form. Choose **Write the abstract**
in your talk's **Workflows** column, then **Engage**, which starts the workflow on your talk, and then **Edit**:

<!-- kdr-walk: workflow=abstract -->
| Field | What to enter |
| --- | --- |
| Summary | How Kotlin coroutines keep a server responsive, told through one request from its arrival to its reply. |
| Duration (minutes) | 45 |
| Topics | Coroutines, Testing |
| Who is the talk for? | People with some experience |
| May we record the talk? | Yes |

Choose **Save the abstract**. A summary has to be at least 50 characters long, so if you write one of your own and it
is shorter, the form says so and does not save.

### Join the committee

Now switch sides. Choose your name in the bar at the top of the page, and choose your **Public** user. Then open the
[Join page for the conference demo](/wa/join/confdemo) again, and choose **Join as Committee member**. (You
acknowledged that the demo is shared when you joined as a speaker, so you are not asked again.)

That gives you a second user in the conference demo, an administrator with the committee's label, so it may review
talks. Both of your demo users have your email address. To tell them apart in the bar, give this one a name of its
own: open your [profile](/wa/profile), and under **Your name**, type

<!-- kdr-walk: name -->
| Field | What to type |
| --- | --- |
| Name | *your first name* (committee) |

and choose **Save name**. The name belongs to this user alone. Your speaker user keeps its own.

### Review your talk

As an administrator, you see every talk in the demo, not just your own, so the menu's forms page is **Forms** rather
than **My forms**. Open it, and type your talk's name in **Search** to find it.

Choose **Committee review** in its **Workflows** column, then **Engage**, and then **Edit**. The review has two steps,
listed at the top: **Record the review**, and **Accept the talk**. The first one asks for:

<!-- kdr-walk: workflow=review -->
| Field | What to enter |
| --- | --- |
| Score | 4 |
| Notes for the speaker | A clear story. Show one test as well. |

Choose **Save the review**. The second step then asks you to read the review and accept the talk. Choose **Accept the
talk**, and confirm. An approval cannot be taken back, which is why it asks.

### See the result as the speaker

Choose your name in the bar again, and switch to your speaker user. Open your talk from **My forms**, and choose
**Committee review**: both steps are checked, and the score and notes are there for you to read. The speaker cannot
change them. Only a committee member may save the review, which is why the speaker's page shows it read-only.

### What you have seen

- **One form, several workflows.** The talk was created by one workflow, completed by a survey, and then worked on by
  two more, each with its own form showing only the fields it needs.
- **A workflow that waits.** The committee review was not available until the survey was complete.
- **Who may do what.** The review could be saved only by a user with the committee's label, and the speaker saw it
  read-only.
- **Several users, one person.** You acted as a speaker and as a committee member, with the same email, switching
  between them from the bar.

Every one of these comes from the demo's configuration. There is no code written just for the conference demo. To see
how each piece is configured, read [the conference demo's pages](demo-client/README.md), which go through its
configuration one workflow at a time.

## Part 2: A client of your own

A client is an organization's own corner of the site: its people, its forms, its workflows, and the words its pages
use. In this part you make one for a small book club, whose members suggest the books it reads next. You build its
form and its workflow in a sandbox, publish them, and invite somebody to try the workflow before everyone else sees it.

A person makes one client of their own. If you have made yours already, use it: the steps are the same, though its
name and its home page are your own.

### Create the client

Choose your name in the bar at the top of the page, and choose your **Public** user. On your home page, find the
**Create your own client** card, and type

<!-- kdr-walk: client -->
| Field | What to type |
| --- | --- |
| Name | Book Club |

Leave everything else as it is, and choose **Create the client**. You become its administrator, and the page moves
there. The client starts with two workflows, **New form** and **Form details**, which let its people make a form and
name it, and with a report, **Forms**, which lists them.

### Open its sandbox

Choose **Open sandbox** in the bar. The bar now shows **Book Club Sandbox**.

Your client's people see only what has been published. A sandbox runs everything you change at once, as a draft,
with users and forms of its own, so you can try a change before anybody else sees it. Every change up to the
publish step is made here.

### Change its words

Open **My client** from the menu, and find its **Copy**. The copy is every word the site's pages and mails use, and
your client may change any of it. Make two changes, one at a time:

<!-- kdr-walk: copy -->
| Words now | Key | New words |
| --- | --- | --- |
| A reference deployment | home.title | Welcome to the Book Club |
| You have been invited | invitation.subject | You are invited to the Book Club |

For each one, type the words it says now into **Find**, and choose **Edit** on the key the table names: the same
words may be said by more than one key. Replace the words, and choose **Save** in the bar beneath them. The first is
the title of your client's home page, and the second is the subject of the mail an invitation sends.

### Design a trait

A **trait** is one part of a form: a set of fields that belong together. Under **Forms and data**, find **Its own
traits**, and choose **New trait**:

<!-- kdr-walk: trait -->
| Field | What to type |
| --- | --- |
| Name | Book suggestion |
| Description | A book somebody suggests the club reads. |

Then give it two fields. The first is there already, and **+ Add field** adds the second. For each, type its label
and choose its kind. A choice field comes with a box for its first choice, and **+ Add choice** adds a box for each of
the others:

<!-- kdr-walk: fields -->
| Label | Kind | Choices |
| --- | --- | --- |
| Title | Text | |
| Genre | Choice | Fiction, Mystery, Science fiction, Biography |

The **Preview** beneath shows the form the trait makes. Choose **Save**. The trait is listed with an id made from its
name, `bookSuggestion`, which never changes, even if you rename the trait.

### Make a workflow

A **workflow** is a process a form goes through, made of steps. Under **Workflows**, find **Make a workflow from a
template**, and choose **Use…** on **Collect information**:

<!-- kdr-walk: newWorkflow -->
| Field | What to enter |
| --- | --- |
| Name | Suggest a book |
| Step | The book |
| Collects | bookSuggestion |

**Collects** lists traits by their ids. Remove **kdr:name**, the form's name, which the template collects to start
with, and choose **bookSuggestion**. Leave **Seen by** as it is, and choose **Make the workflow**.

### Try it in the sandbox

Open **Forms** from the menu, and choose **Create a form**. The form that opens is **New form**:

<!-- kdr-walk: sandboxForm -->
| Field | What to type |
| --- | --- |
| Name | A first suggestion |

Choose **Create form**. In your form's **Workflows** column, choose **Suggest a book**, then **Engage**, and then
**Edit**:

<!-- kdr-walk: sandboxSuggestion -->
| Field | What to enter |
| --- | --- |
| Title | The Hobbit |
| Genre | Fiction |

Choose **Save**.

### Change the form in Design View

Still on the form, choose **Design** in the bar. Each part of the form is outlined as you point at it, and choosing a
field opens what is behind it at the side of the page. Make two changes to this workflow's form:

<!-- kdr-walk: design -->
| Field | Change |
| --- | --- |
| Title | Label: Book title |
| Genre | Remove the choice: Biography |

Choose the **Title** field on the form, and under **Copy for this workflow**, choose **Override for this workflow**.
Type the new label, and choose **Save**. Then choose the **Genre** field, and **Override for this workflow** again.
Under **Choices on this form**, each choice has a box, named by the value the trait stores for it: uncheck
**biography**, and choose **Save**. Choose **Design** again to leave Design View.

Both changes belong to this workflow alone. The trait keeps its label and all four choices, so another workflow
collecting it still offers **Biography**. A form may offer fewer choices than its trait, but never more, which is
why the change removes a choice rather than adding one.

### Publish

Open **My client** again, and find **Publishing**. It lists the configuration the sandbox runs, and every change you
have made is in a configuration not yet published. Choose **Select all unpublished**, and then **Publish selected**.
They are published together, and your client's people now have them.

### Back to Book Club

Choose **Book Club Sandbox** in the bar, and then **Back to Book Club**. The home page now says **Welcome to the Book
Club**. The form you made in the sandbox stays there, with the sandbox's other data.

### Invite somebody

Open **Users** from the menu, and choose **Create user**:

<!-- kdr-walk: invite -->
| Field | What to type |
| --- | --- |
| Email address | *an address of yours* |

The site sends a real invitation to this address, so use one whose mail you can read. Many mail services deliver
an address with a `+` and a word added to its name, such as `you+test@example.com`, to `you@example.com`, which
suits this step. Choose **OK**. The invitation's subject is **You are invited to the Book Club**.

Then choose the new user's row, and give it a label: type it in **Labels**, and press Enter.

<!-- kdr-walk: userLabels -->
| Field | What to enter |
| --- | --- |
| Labels | pilot |

Choose **OK**. A label grants nothing by itself. Its use is in what a workflow does with it, which is the next step.

### Let only the pilot see the workflow

A workflow made from a template is seen at first only by the client's administrators. Open **My client**, and under
**Workflows**, choose **Visibility…** on **Suggest a book**. **Seen by** already says what the table does, so only
the label is needed: type it in **Labels**, and press Enter.

<!-- kdr-walk: visibility -->
| Field | What to choose |
| --- | --- |
| Seen by | Administrators, and people with a label |
| Labels | pilot |

Choose **Save**. Now the workflow is seen by you, and by anyone with the `pilot` label.

### Accept the invitation and suggest a book

Open the invitation in a private window of your browser, so that you stay signed in here. Its link opens a page
saying what the account is. Choose **Accept and sign in**, and you are signed in as the new user.

As that user, open **My forms**, and choose **Create a form**:

<!-- kdr-walk: invitedForm -->
| Field | What to type |
| --- | --- |
| Name | Our next read |

Choose **Create form**. Then choose **Suggest a book** in its **Workflows** column, then **Engage**, and then
**Edit**:

<!-- kdr-walk: invitedSuggestion -->
| Field | What to enter |
| --- | --- |
| Book title | Dune |
| Genre | Science fiction |

The title's label is the one you gave it in Design View, and **Genre** does not offer **Biography**. Choose
**Save**, and close the private window.

### Open the workflow to everyone

Back in your own window, open **My client**, and choose **Visibility…** on **Suggest a book** again. Choose
**Everyone** for **Seen by**, and choose **Save**. Everyone in your client now sees the workflow on their forms. Like
the label before it, this change takes effect at once, with no draft and no publish.

### Read the report

Open **Reports** from the menu, and choose **Forms**. It opens grouped, counting your client's forms by their status.
Choose **Per form** to see each one, with its name, its owner, its status, and when it was made. The form you made in
the sandbox is not there: it belongs to the sandbox.

### What you have seen

- **A sandbox before your people.** Everything you changed ran in the sandbox first, and reached your client's people
  only when you published it.
- **Words are configuration too.** The home page's title and the invitation's subject changed without a line of code.
- **A trait, a workflow, and a form.** The trait says what can be recorded, the workflow which step records it, and
  Design View how that step's form asks for it.
- **A workflow tried with a few people first.** A label let one person use the workflow before it was opened to
  everyone.
