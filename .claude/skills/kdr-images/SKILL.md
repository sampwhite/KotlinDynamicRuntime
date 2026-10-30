---
name: kdr-images
description: Draw, place, and check an image KotlinDynamicRuntime ships — an SVG or PNG a component serves for copy (`![alt](img:<name>)`) or for a document (`docs/images/`, named by its repo path), its declaration (`componentImage`, `sourcePath`), what the boot refuses, and the house look (app.css palette, its own ground for GitHub, the 24-pixel stroke icon grid, one font stack, title/alt/comment). Includes `banner.py`, a generator for step-strip banners with the shared icon set. Use when adding or changing an image in copy, a document, or a page, or when a session is about to draw an SVG by hand.
---

# Images the runtime ships (issue #1465)

Six SVGs were drawn from scratch in separate sessions before this skill, and their look drifted: colors picked by
eye, stroke widths and fonts that vary, a background on some and not others. The code checks what is *safe*; nothing
checks what is *consistent*. This skill is that half. Start from it, and from [`banner.py`](banner.py) beside it,
instead of a blank file.

## Where an image lives and how it is named

Every image the runtime serves is a **component image** (issue #1339): declared by a component, read from its
classpath at boot, checked, and served at `/<staticRoot>/<appId>/img/<name>:<hash>`, which a browser keeps for good.
Two places name one:

| Shown in | File | Declared | Named from the Markdown as |
| --- | --- | --- | --- |
| **Copy** (a fragment, a page's words) | the module's `src/main/resources/component-images/` | `componentImage(name, fileName)` | `![alt](img:<name>)` |
| **A document** (`docs/…`, served in-app *and* read on GitHub) | `docs/images/`, copied onto the classpath by the module's build | `componentImage(name, fileName, sourcePath = "docs/images/<file>")` | `![alt](images/<file>)`, its path relative to the document |

```kotlin
// In the component (kdn's WalkThrough.images, issue #1463): the name is rooted under the component's owner root.
override fun images(cxt: KdrCxt): List<ComponentImageDef> = listOf(
    componentImage("kdr:walkPart1", "walk-part1.svg", sourcePath = "docs/images/walk-part1.svg"),
)
```

`ImagesSkillExamplesTest` (`base/common`) holds this example, and the refusals below, to the code.

A document image needs both halves. The **`sourcePath`** is what lets one plain Markdown image draw in both places:
GitHub draws the file, and the app's document link resolver (`resolveDocLink`, fed the shell config's `imagePaths`)
turns a link to that path into the served image's address. Without it, the app sends the link to GitHub's blob page,
which is HTML, and draws a broken image. The **build copy** is a `Copy` task into `build/docResources/component-images`,
as kdn's `embedImages` does beside its `embedDocs`. Keep the file once, in `docs/images/`; never a second copy in
`src/main/resources`.

The core images are in `base/common` (`email-to-users.png`, `file-refs.svg`, `sandbox.svg`, declared in
`CommonComponent.images`), the sample's in `sample` (`site-visit-flow.svg`), and the document images in `docs/images/`:
the README's banner (`overview.svg`, common's `embedImages`) and the walk-through's (kdn's).

### What the boot refuses

`ComponentImageService` checks every declared image at startup. A problem refuses the boot outside `prod`, and in
`prod` is reported and the image left out, so copy naming it draws its alt text (`KDR_IMAGE_CHECK`:
`strict`, `warn`, or `off`). It refuses:

- a **name** not rooted under the declaring component's owner root (`kdr:` for core and kdn, `sample:` for the
  sample), unless `contributesTo` names the root it is under, and a name declared twice;
- a **file name** that is not a plain name with a lowercase extension, a file not on the classpath (a forgotten build
  copy shows up here), and one over **2 MB**;
- a file that is not **PNG, JPEG, WebP, ICO, or an SVG with nothing in it to run** (`WebAssetCheck`): well-formed XML
  whose root is `svg`, with no `script` element, no `on…` attribute, and no `javascript:` address;
- a **`sourcePath`** that is not a clean repo path (no leading slash, no `.` or `..`), and one two images share.

Copy every client shares that names an `img:` no component ships is a boot problem too, and a client's copy naming
one is refused when its configuration is written.

## The house look

**SVG by default.** It is text, small, diffable, and sharp at any width. PNG only for what is not drawn: a screenshot
or a photo (`email-to-users.png`).

**Colors from `app.css`** (`webapp/src/jsMain/resources/app.css`), each named in the script or the comment by its
token. A hue the app has none of is allowed when the image needs one, and is named as not a token:

| Token | Hex | Use |
| --- | --- | --- |
| `--surface-page` | `#0F172A` | the app's page |
| `--surface-raised` | `#1E293B` | an image's own ground (a card's surface) |
| `--border` | `#334155` | the ground's edge, hairlines |
| `--text` | `#E2E8F0` | text that must read first |
| `--text-dim` | `#CBD5E1` | labels |
| `--text-muted` | `#94A3B8` | secondary lines, neutral strokes |
| `--accent-bright` | `#818CF8` | the indigo accent |
| `--design` | `#2DD4BF` | teal: Design View's, and a second accent |
| `--sandbox` | `#14B8A6` | the sandbox, and only the sandbox |
| `--success` / `--danger` | `#4ADE80` / `#F87171` | a state that means that, never decoration |

**Ground.** An image that is **only ever shown in the app** may be transparent and drawn for the app's dark surface,
as `sandbox.svg` and `file-refs.svg` are. An image **GitHub also shows** carries its own ground: a rounded
`--surface-raised` rect with a `--border` edge, so it reads on GitHub's light pages and dark ones alike.

**Icons** are strokes on a **24-pixel grid**: `fill="none"`, `stroke-width="1.7"`, round caps and joins, scaled as a
group (`transform="translate(x y) scale(s)"`). Reuse one from `banner.py`'s `ICONS` before drawing another, and add a
new one there so the next image has it.

**Type** is one stack: `system-ui, -apple-system, Segoe UI, Helvetica, Arial, sans-serif` (the earlier images'
`sans-serif` and `monospace` predate this). Text in an image does not scale down gracefully, so size it for the width
the image is shown at, and keep words few.

**What the image says about itself:**

- `role="img"` and a **`<title>`** saying what it shows, in a sentence: the accessible name.
- **Alt text** in the Markdown that lists what it shows, briefly. It is what a reader sees wherever the image cannot
  be drawn: a mail, a deployment that left it out, a path no image declares.
- A **comment** after the `<title>`: the issue, what the image is for and where it is shown, and, for a generated
  one, that it is generated and from what.

**Placement in copy.** `![alt](img:<name>){.float-left width=140}` floats an image beside the copy that follows, and
`[]{.clear}` ends the float (issues #795, #1382). That suffix is ours: GitHub shows it as literal text, so an image in
a **document** is a plain one, full width.

## Banners: `banner.py`

[`banner.py`](banner.py) draws a **step strip**: a 960x160 banner naming a part and showing its steps as a row of
icons with labels, as the walk-through's three part banners are. It is a tool for whoever draws an image, not part of
the build; nothing compiles or runs it, so it is checked only when it is run.

```bash
python3 -I .claude/skills/kdr-images/banner.py docs/images
```

To make another, add an entry to `PARTS` (its file, kicker, title, accent as a `PALETTE` key, comment lines, `<title>`
text, and steps as `(icon, label)`), run it, and commit the script and what it wrote together. Run unchanged, it
rewrites the walk-through's banners and the README's byte for byte, which is a quick check that an edit to the layout
left them alone.

**Its limit:** the labels are drawn into the image, so on a phone they shrink with it (a 960-wide strip at 375 pixels
draws 13-pixel labels at about 5). Fine for a banner whose alt text lists the steps; not for an image whose words are
the point. A diagram such as `sandbox.svg` is a one-off: draw it by hand, with the conventions above.

## Checking

1. **The file alone.** Open it in the browser pane (`navigate` to `file:///…`) and look: an SVG that is wrong is
   wrong at a glance.
2. **In the app,** on your own server (see the kdr-testing skill): the page or copy that shows it, and in the page,
   that each image loaded rather than drew its alt text:
   `[...document.querySelectorAll('.markdown img')].map(i => [i.getAttribute('src'), i.complete && i.naturalWidth > 0])`.
   Then narrow (`resize_window` with `mobile`) and look again. A webapp change is not served until the server
   restarts.
3. **On GitHub,** for a document image: once pushed, the document's page on the branch draws it from the file.
4. **The boot.** A missing build copy or a refused SVG fails the boot outside `prod`, so any test booting the declaring
   component catches it; a document's own test can check its images resolve, as `PublicWalkThroughTest` does.
