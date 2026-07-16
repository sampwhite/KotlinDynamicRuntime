# Webapp static resources

The app's shell, stylesheet and artwork. Authored **once** here and served by *both* shells: the webpack dev
server serves this directory from the origin root, and `appui` embeds these files from `:webapp`'s
distribution (see its `embedWebapp` task) to serve them same-origin under the app context root (`/wa`) in
production. Only files `appui` lists are embedded; this README is not.

| File | Role |
|------|------|
| `index.html` | The **dev** shell. Production's counterpart is `appui`'s `AppUiPage` (a Kotlin string, so it can inject the live context roots). |
| `app.css` | The stylesheet — **both** shells link it. See the note at its top before adding CSS anywhere else. |
| `favicon.svg` | Tab icon. The mark drawn with heavier strokes, for 16px legibility. |
| `brand-mark.svg` | The master mark, at display size: the app bar lockup and the home hero. |
| `favicon.ico` | Legacy tab icon (16+32+48 embedded). See the caveat below. |
| `favicon-32.png` | Tab-icon fallback for browsers that don't take the SVG. |
| `apple-touch-icon.png` | 180×180, opaque background — iOS home screen. |

## These are the *defaults* — a deployment can override them

The artwork here is the built-in set, not the only possible one. A deployment names its own classpath
directory with the `appUiBrandingDir` config key and ships its files there; `appui` then serves those bytes at
the same URLs, falling back to these per asset. So a deployment overrides just its logo and inherits the rest,
without forking this module. See `examples/custom-config.md`.

The dev server always serves *this* set — it has no config. Only the deployed app is brandable.

## The artwork is Fable's — don't redraw it here

Every icon is copied **verbatim** from the branding set (`~/Downloads/branding`, whose README is the spec).
That set is the source of truth and holds more than this: the master mark, a `currentColor` mono variant, and
`favicon-16/48` plus `icon-192/512`. Regenerate there, then re-copy — do not hand-edit these files.

The SVGs carry their own header comments explaining their roles. The rasters cannot, which is why this file
exists.

## What is deliberately not here, and why

- **`favicon-16.png` / `favicon-48.png`** — `favicon.ico` already embeds both sizes, and the branding
  README's integration snippet links only the 32.
- **`icon-192.png` / `icon-512.png`** — PWA manifest icons. There is no web app manifest, so wiring them up
  would serve bytes nothing requests. Add them *with* a manifest or not at all.

## The `favicon.ico` caveat

Browsers request `/favicon.ico` by convention, **without a link tag — at the origin root**. This app is served
under a context root (`/wa`), and the runtime's dispatcher 404s an unknown root, so that automatic request
never reaches this file. It is served at `/wa/favicon.ico` and is therefore only reached by a deployment that
fronts the app at the origin root. The SVG and PNG links are what actually cover browsers today.
