/- Copy for the Clients page (issue #1337): for now, the "What is this?" explanations of its panels and the definition's
   groups (issue #1397), each in the namespace of what it explains, and the note a new workflow's maker reads (issue
   #1384). The page's other words are still in the webapp, and belong here as they are moved. Named by the shell's
   UI-config after the home file, since the page has no UI-config of its own. -/

# @about
# +whatIsThis
Who the client is.

- **Client id** names the client everywhere it is stored, and never changes.
- **Name** is what the client's people see it called.
- **Description** is an internal note about who, what, or why, which only administrators see.


# @behavior
# +whatIsThis
How the client behaves for its people.

- **Time zone** is the zone its dates and times are shown and entered in. Changing it moves no stored moment.
- **User labels** are words an administrator puts on this client's users, such as `reviewer` or `siteLead`. A
  label grants nothing by itself, but workflows rely on them. A workflow can be **seen** only by users carrying a
  label, so a workflow for site leads is shown to site leads alone. And an approval step is taken by a user carrying
  the label it names, such as `reviewer`, so a review workflow has nobody to approve its forms until some user is
  given that label.
- **Session hours** is how long a sign-in lasts before the person must sign in again.
- **Refresh interval seconds** is how often an open page that nobody is using reads what it shows again.
- **Listing columns** are columns the forms listing adds for this client's forms, each showing a field of a trait.
  Suppose a conference-session trait records which **track** a session belongs to. With a column for it, the listing
  shows each form's track at a glance, without opening the form, and the forms can be searched and sorted by
  track.
- **Traits**, where listed here, are the kinds of information its forms may hold. A client whose configuration is
  edited on this page chooses them under **Forms and data**.


# @platform
# +whatIsThis
What the platform sets about the client. A client's own administrator sees these, and cannot change them.

- **Audience** is whose the client is: the deployment's own work (`internal`), or a customer's (`customer`).
- **Usage type** is what the client is for: `production`, `dev`, `test`, `demo`, or a `template` other clients are
  built on.
- **Customer id** is the customer the client belongs to, when it belongs to one.
- **Extends** names a template client this one is built on. Its traits, workflows, and copy are this client's
  defaults, which this client's own configuration replaces or rewords.
- **Domain** is the address the client's people reach it at: a prefix of the deployment's domain, or a domain of its
  own.
- **Web resources** names the package of web resources the client presents.


# @runs
# +whatIsThis
Where the client is defined, and where it runs.

- **Definition** says where the client's definition comes from: written in **source** code, which changes with a
  release, or **stored** configuration, which is changed on this page.
- **Enabled in** lists the environments that load the client, such as `local` and `production`.
- **Static config** means that in production the client takes no stored configuration, only what source code says.
  Elsewhere it is edited as any client is, and the changes are brought into source code.
- **Preload** means the client's caches are computed before a node reports that it is ready.
- **Plan** is what the client may hold, and how much of it is used.


# @formsData
# +whatIsThis
What the client's forms hold, and the steps people take to fill them in.

- **Traits** are kinds of information, such as an expense report or a site visit, each with its own fields. A form
  holds the traits its workflows collect. A workflow can collect only the traits the client has: the platform's
  global traits it chooses to include, and the traits it defines for itself.
- **Workflows** are what people do with a form: create it, fill it in, approve it. Each is made from one of the
  deployment's templates. The template fixes the steps and their rules, and the client names each step and chooses
  the traits it collects.
- **Who and when.** A workflow can be limited to the users carrying one of the **user labels** above, so that only
  they see it. Its **time windows** say when it is available at all, and when new forms may join it.


# @formStates
# +whatIsThis
Each form stores its **state**: which workflows it can take part in, and where it stands in each. The state is
worked out from the form's data and the client's configuration, and it is stored again whenever someone saves the
form. A change to the configuration alone leaves the forms nobody has saved since with their old state, so they
do not offer a new workflow, for example. A recalculation runs every night and catches up, and **Recalculate form
states** does it now. Recalculate after you:

- **add a workflow**, so that the forms already there offer it;
- **change a workflow's entry rules**, such as its tests or its time windows, so that each form is offered the
  workflows it now qualifies for;
- **publish** such a change, for a client with a sandbox, since the change applies only once it is published.

Recalculating changes no data anybody entered, and keeps every workflow a form has entered and every approval it
has. It runs while you wait, so it is offered only for a client holding no more forms than the limit the page
gives; a larger client is brought up to date by the nightly run.


# @copy
# +whatIsThis
**Copy** is the words the application shows this client's people: headings, help, buttons, and mails. The client can
reword any of it.

- A **file** holds the copy for one part of the application. For example, `home` holds the words of the app bar and
  the home page, and `clients` holds this page's explanations, this one included.
- Within a file, copy is grouped into **namespaces**, and each piece has a **key**, such as `forms.whatIsThis`.
- Choose a file to see every key with what it says, or type in **Find** to search every file by its words. **Edit**
  a key to reword it for this client. **Reset** brings back the words the application shipped with.

Copy is configuration: for a client with a sandbox, a change is a draft until it is published.


# @images
# +whatIsThis
The images this client's people see.

- **Web assets** are the tab icon and the brand mark the app bar shows, in place of the deployment's. A change is
  live at once for everyone in the client, with no draft.
- **Files** are named images that this client's copy can show. They are configuration, as copy is: for a client
  with a sandbox, a change is a draft until it is published.


# @menu
# +whatIsThis
The **menu** is the list under the app bar's ☰ button. The client can rename an item, hide it, or show it to a
different audience, such as everyone signed in or administrators only.

Hiding or showing an item changes what is offered, not who may reach the page behind it: a page refuses anybody
not allowed to use it, whatever the menu says. **Reset** puts an item back as shipped.

Most of the shipped menu is for the deployment's own operators, test instances, and debugging, which this client's
people are never offered. The list shows only the items they may be, unless asked for every one.


# @sandbox
/- The last paragraph names controls of the app bar (Open sandbox, the sandbox's chip and its Back to, the switcher
   under the user's name), which live elsewhere: keep it in step with the bar when they change (issue #1397). -/
# +whatIsThis
![A change is saved as a draft the sandbox runs, and is published to the client's people](img:kdr:sandbox)

A sandbox is where this client's changes are tried before its people see them.

- **Without one,** a saved change to the client's configuration is live for its people at once.
- **With one,** the client's people run only **published** configuration. Every change, made on this page or in the
  sandbox, is a draft until it is published.

The sandbox runs the drafts, with users and data of its own, so a change can be seen as the client's people will
see it before it reaches them. Open the sandbox to try a change, and publish from the sandbox's page.

**Getting there.** As an administrator of this client, choose **Open sandbox** in the bar at the top of the page.
You become a second version of yourself: a user of the sandbox, also an administrator. The bar then shows the
sandbox's name in place of Open sandbox, and **Back to** the client returns you to your usual self. Both versions of
you are also listed in the menu under your name in the bar, and choosing one switches to it.


# @storedConfig
# +whatIsThis
A client's configuration is kept in named parts, one row each: its definition, its copy, a trait or a workflow of its
own. Every saved change makes a new **revision** of its part.

- **Live or draft.** A client without a sandbox runs each part's latest revision, so a change is live once it is
  saved. A client with a sandbox runs only what was published, so its latest revision may be a draft: "v3 draft; v2
  live".
- **Publishing** makes the latest revisions live. Select the parts and publish them together, so that changes which
  only work as a set, such as a workflow moved from one part to another, go live at once. A client with a sandbox
  publishes from the sandbox's page, where its drafts can be seen first.
- **Check impact** says first what publishing would do to the forms the client already holds. A publish that would
  change them asks before it goes ahead.

Configuration written in source code is not listed here: it changes with a release of the application.


# @files
/- The Files explanation shows its own image, a component's (`img:kdr:fileRefs`), and walks a client through replacing
   it with one of its own files: the concept, practiced on the text that explains it. Keep the steps' words in step
   with the page's controls (Add a file, Id, Upload..., Copy, Edit, Insert an image..., Save, Reset), and keep
   the first line the image, which step 3 points at. -/
# +whatIsThis
![Copy names an image by where it comes from](img:kdr:fileRefs)

**Files** are named images that this client's copy can show. Copy names an image by where it comes from, never by
its contents:

- `img:` names an image **shipped with the application**, the same for every client, such as the diagram above.
- `ref:` names one of **this client's files**, by the id it is listed under here. Replace a file under the same id,
  and every piece of copy showing it changes with no edit to the copy.

Files are configuration, like copy: for a client with a sandbox, a new or replaced file is a draft until it is
published. **Web assets**, above, are different: a change to one is live at once, with no draft.

To try it, make this explanation show an image of your own:

1. Under **Add a file** below, give an **Id** such as `ourDiagram`, and **Upload…** an image.
2. In **Copy**, further up this page, choose the file `clients`, and find the key `files.whatIsThis`: this
   explanation.
3. **Edit** it. Its first line shows the diagram above, as `![…](img:kdr:fileRefs)`. Replace that line with
   `![Our diagram](ref:ourDiagram)`. Or delete it, leave the cursor where it was, and choose your file under
   **Insert an image…**, which writes the line for you.
4. **Save**. This explanation now shows your image to this client's people, as a draft first for a client with a
   sandbox. **Reset** on the key puts the shipped explanation back.

Upload the image first: copy naming a file the client does not have is refused when it is saved.


# @workflows
/- Shown under "Seen by" when a workflow is made from a template (issue #1384). Empty as shipped: the form already says
   who sees the new workflow, and a deployment that teaches -- the `reference` flavor -- adds how to open one up. -/
# +newWorkflowNote
