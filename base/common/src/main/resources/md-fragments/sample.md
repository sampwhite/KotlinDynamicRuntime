/- A sample Markdown fragment file (issue #59). Comments like this one are stripped before parsing.
   Fragment values may themselves contain ${...} placeholders, which the frontend resolves at render time. -/

# @email

# +subject Your verification code

# +body
Hello,

Your verification code is **${code}**.

## Note
It expires in ${minutes} minutes.

# @portal

# +welcome Welcome to the portal.
