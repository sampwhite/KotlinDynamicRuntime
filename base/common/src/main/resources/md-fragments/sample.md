/- A sample Markdown fragment file (issue #59). Comments like this one are stripped before parsing.
   Fragment values may themselves contain ${...} placeholders, which the frontend resolves at render time. -/

/-where none: a fixture no page shows. -/
# @email

# +subject Your verification code

# +body
Hello,

Your verification code is **${code}**.

## Note
It expires in ${minutes} minutes.

/-where none: a fixture no page shows. -/
# @portal

# +welcome Welcome to the portal.
