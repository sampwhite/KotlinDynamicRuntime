/- Copy for the Clients page (issue #1337): for now, the "What is this?" explanations of its sections, each in the
   namespace of the section it explains. The page's other words are still in the webapp, and belong here as they are
   moved. Named by the shell's UI-config after the home file, since the page has no UI-config of its own. -/

# @sandbox
# +whatIsThis
A sandbox is where this client's changes are tried before its people see them.

- **Without one,** a saved change to the client's configuration is live for its people at once.
- **With one,** the client's people run only **published** configuration. Every change, made on this page or in the
  sandbox, is a draft until it is published.

The sandbox runs the drafts, with users and data of its own, so a change can be seen as the client's people will
see it before it reaches them. Open the sandbox to try a change, and publish from the sandbox's page.


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
   with the page's controls (Add a file, Id, Upload..., Copy & menu, Edit, Insert an image..., Save, Reset), and keep
   the first line the image, which step 3 points at. -/
# +whatIsThis
![Copy names an image by where it comes from](img:kdr:fileRefs)

**Files** are named images that this client's copy can show. Copy names an image by where it comes from, never by
its contents:

- `img:` names an image **shipped with the application**, the same for every client, such as the diagram above.
- `ref:` names one of **this client's files**, by the id it is listed under here. Replace a file under the same id,
  and every piece of copy showing it changes with no edit to the copy.

Files are configuration, like copy: for a client with a sandbox, a new or replaced file is a draft until it is
published. **Web assets**, above, are different. They are the tab icon and the brand mark the app shows this
client's people, and a change to one is live at once, with no draft.

To try it, make this explanation show an image of your own:

1. Under **Add a file** below, give an **Id** such as `ourDiagram`, and **Upload…** an image.
2. In **Copy & menu**, further down this page, choose the file `clients`, and find the key `files.whatIsThis`:
   this explanation.
3. **Edit** it. Its first line shows the diagram above, as `![…](img:kdr:fileRefs)`. Replace that line with
   `![Our diagram](ref:ourDiagram)`. Or delete it, leave the cursor where it was, and choose your file under
   **Insert an image…**, which writes the line for you.
4. **Save**. This explanation now shows your image to this client's people, as a draft first for a client with a
   sandbox. **Reset** on the key puts the shipped explanation back.

Upload the image first: copy naming a file the client does not have is refused when it is saved.
