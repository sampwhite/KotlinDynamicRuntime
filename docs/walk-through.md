<!-- The walk-through for a person signed in to a reference deployment (issue #1429), linked from their home page.
     Its tables are what the reader types, and PublicWalkThroughTest (base/kdn) enters each one, through the same
     endpoints the pages use, so a label or a choice the demo renames fails the build. A table is marked on the line
     before it: `kdr-walk: workflow=<id>` for a form of that workflow, `kdr-walk: name` for a name on the profile.
     `*your first name*` is a placeholder the test fills in. -->

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
