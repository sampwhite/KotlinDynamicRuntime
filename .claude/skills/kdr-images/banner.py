"""Draws a step-strip banner: a part's steps as a row of stroke icons, the part named above them (issue #1465).

The walk-through's part banners (docs/images/walk-part<n>.svg, issue #1429) are what the PARTS list draws, byte for
byte; to make another, add an entry and run with the output directory:

    python3 -I .claude/skills/kdr-images/banner.py docs/images

The conventions it follows are the kdr-images skill's (SKILL.md beside it, issue #1465): colors by app.css token
(PALETTE), stroke icons on a 24-pixel grid (ICONS, the reusable part), one font stack. Not in the build: nothing
compiles or runs it, so it is checked only when it is run. View what it writes in the browser before committing it.
"""
import sys

ICONS = {  # stroke icons on a 24x24 grid
 'badge': '<rect x="6" y="3" width="12" height="18" rx="2"/><circle cx="12" cy="10" r="2.5"/><path d="M9 16 H15"/>',
 'slide': '<rect x="3" y="4" width="18" height="12" rx="1"/><path d="M12 16 V20 M8 20 H16"/>',
 'survey': '<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M8 8.5 L9.5 10 L12.5 7 M8 14 H16 M8 17 H14"/>',
 'pencil': '<path d="M4 20 V16 L15 5 L19 9 L8 20 Z M13 7 L17 11"/>',
 'people': '<circle cx="9" cy="8" r="3"/><circle cx="17" cy="9" r="2.5"/><path d="M3 20 C3 16 6 14 9 14 S15 16 15 20 M15.5 14.2 C18.5 14.2 21 16 21 19.5"/>',
 'star': '<path d="M12 3 L14.6 8.9 L21 9.5 L16.2 13.8 L17.6 20.1 L12 16.8 L6.4 20.1 L7.8 13.8 L3 9.5 L9.4 8.9 Z"/>',
 'check': '<circle cx="12" cy="12" r="9"/><path d="M8 12 L11 15 L16 9"/>',
 'building': '<path d="M4 21 V7 L12 3 L20 7 V21 Z M9 21 V16 H15 V21 M8 9 H10 M14 9 H16 M8 12.5 H10 M14 12.5 H16"/>',
 'sandbox': '<rect x="3" y="5" width="18" height="14" rx="2" stroke-dasharray="3 2.5"/><path d="M8 12 H16 M13 9 L16 12 L13 15"/>',
 'words': '<path d="M3 19 L8 5 L13 19 M5 14 H11"/><path d="M20 19 V13.5 C20 11.5 18.8 10.5 17 10.5 C15.6 10.5 14.6 11.1 14.2 12 M20 15 H17 C15.3 15 14.3 15.8 14.3 17 C14.3 18.3 15.3 19 16.6 19 C18.3 19 20 17.8 20 16"/>',
 'fields': '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M6.5 8.5 H11 M6.5 12 H17.5 M6.5 15.5 H14"/>',
 'flow': '<circle cx="4.5" cy="12" r="2"/><rect x="9" y="8.5" width="7" height="7" rx="1.5"/><circle cx="19.5" cy="12" r="2"/><path d="M6.5 12 H9 M16 12 H17.5"/>',
 'publish': '<path d="M12 15 V4 M7 9 L12 4 L17 9 M4 15 V20 H20 V15"/>',
 'mail': '<rect x="3" y="6" width="18" height="12" rx="1.5"/><path d="M3.5 7 L12 13 L20.5 7"/>',
 'bars': '<path d="M6 20 V12 M12 20 V5 M18 20 V14 M3.5 20.5 H20.5"/>',
 'key': '<circle cx="8" cy="15" r="4"/><path d="M11 12 L20 3 M16.5 6.5 L19 9 M14.5 8.5 L16.5 10.5"/>',
 'braces': '<path d="M9 4 C7 4 6 5 6 7 V10 C6 11 5 12 4 12 C5 12 6 13 6 14 V17 C6 19 7 20 9 20 M15 4 C17 4 18 5 18 7 V10 C18 11 19 12 20 12 C19 12 18 13 18 14 V17 C18 19 17 20 15 20"/>',
 'terminal': '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 10 L10 12 L7 14 M12 15 H17"/>',
 'spark': '<path d="M12 3 L13.8 9.2 L20 11 L13.8 12.8 L12 19 L10.2 12.8 L4 11 L10.2 9.2 Z M19 3.5 V7 M17.25 5.25 H20.75"/>',
 'revoke': '<circle cx="12" cy="12" r="9"/><path d="M5.6 5.6 L18.4 18.4"/>',
}
# The colors, by the app.css token each one is (webapp/src/jsMain/resources/app.css). Two are not tokens, and say so.
PALETTE = {
    'surface-raised': '#1E293B',   # the ground: a card's surface
    'border': '#334155',           # the ground's edge
    'text-dim': '#CBD5E1',         # labels
    'accent-bright': '#818CF8',    # an accent: indigo
    'design': '#2DD4BF',           # an accent: teal, Design View's
    'amber': '#FBBF24',            # an accent: no token; the app's amber (border-admin) is too dark on the ground
    'connector': '#475569',        # the dotted line between steps; no token, a step between border and text-faint
}
FONT = 'system-ui, -apple-system, Segoe UI, Helvetica, Arial, sans-serif'

# What every walk-through banner says about itself, in a comment: its issue, what it is for, and how it was made.
WALK_ABOUT = [
    "  <!-- A banner opening a part of the walk-through (docs/walk-through.md, issue #1429): the part's steps in order.",
    "       Drawn on its own dark ground so it reads on GitHub's light and dark pages as in the app. Generated with the",
    "       other parts' banners from one layout; edit the three together. -->",
]

# Each banner: its file, kicker, title, accent (a PALETTE key), comment lines, <title> text, and steps as (icon, label).
PARTS = [
 ('walk-part1.svg', 'Part 1', 'The conference demo', 'accent-bright', WALK_ABOUT,
  'Part 1 of the walk-through: join the conference demo as a speaker, submit a talk, answer the survey, write the abstract, join the committee, review the talk, and see the result.',
  [('badge','Join'),('slide','Submit a talk'),('survey','Survey'),('pencil','Abstract'),('people','Committee'),('star','Review'),('check','Result')]),
 ('walk-part2.svg', 'Part 2', 'A client of your own', 'design', WALK_ABOUT,
  'Part 2 of the walk-through: create a client, open its sandbox, change its words, design a trait, make a workflow, publish, invite somebody, and read the report.',
  [('building','Client'),('sandbox','Sandbox'),('words','Words'),('fields','Trait'),('flow','Workflow'),('publish','Publish'),('mail','Invite'),('bars','Report')]),
 ('walk-part3.svg', 'Part 3', 'For developers', 'amber', WALK_ABOUT,
  'Part 3 of the walk-through: make an API key, find an endpoint in the catalog, call it, have an AI assistant build a workflow, and revoke the key.',
  [('key','API key'),('braces','Catalog'),('terminal','Call it'),('spark','AI builds'),('flow','Workflow'),('revoke','Revoke')]),
]

# The layout: a 960x160 strip, the steps spread evenly 90 in from each side, 26-radius circles, 13px labels.
W, H, cy, r = 960, 160, 86, 26
C = PALETTE


def banner(part, title, accent, about, desc, steps):
    accent = C[accent]
    n = len(steps); left, right = 90, W - 90
    xs = [left + (right - left) * i / (n - 1) for i in range(n)]
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img">',
         f'  <title>{desc}</title>',
         *about,
         f'  <rect x="1" y="1" width="{W-2}" height="{H-2}" rx="14" fill="{C["surface-raised"]}" stroke="{C["border"]}" stroke-width="2"/>',
         f'  <text x="28" y="36" font-family="{FONT}" font-size="14" font-weight="700" letter-spacing="2" fill="{accent}">{part.upper()}</text>',
         f'  <text x="{28 + 14*len(part)*0.78 + 14}" y="36" font-family="{FONT}" font-size="14" letter-spacing="1" fill="{C["text-dim"]}">{title}</text>',
         f'  <path d="M{xs[0]+r} {cy} H{xs[-1]-r}" stroke="{C["connector"]}" stroke-width="2" stroke-dasharray="2 6" stroke-linecap="round"/>']
    for x, (icon, label) in zip(xs, steps):
        o.append(f'  <circle cx="{x:.1f}" cy="{cy}" r="{r}" fill="{accent}" fill-opacity="0.14" stroke="{accent}" stroke-width="2"/>')
        o.append(f'  <g transform="translate({x-15:.1f} {cy-15}) scale(1.25)" fill="none" stroke="{accent}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">{ICONS[icon]}</g>')
        o.append(f'  <text x="{x:.1f}" y="{cy+r+24}" text-anchor="middle" font-family="{FONT}" font-size="13" fill="{C["text-dim"]}">{label}</text>')
    o.append('</svg>')
    return '\n'.join(o) + '\n'


if __name__ == '__main__':
    out = sys.argv[1]
    for fname, *spec in PARTS:
        with open(f'{out}/{fname}', 'w') as f:
            f.write(banner(*spec))
