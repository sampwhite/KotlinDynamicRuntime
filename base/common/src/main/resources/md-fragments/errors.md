/- General error copy owned by the request handler, not any one feature (issue #108). Rendered server-side
   from KdrException's KdrMsg, like auth's error copy. Kept here (not auth.md) because the obfuscation
   mechanism is generic: any subsystem may flag an error `sensitive`, and this is the message its client sees
   when a deployment obfuscates. Shown to the user as designed copy (errorFromFragment=true). -/

# @general
/- The stand-in for a `sensitive` error's real message when a deployment obfuscates it -- deliberately vague,
   revealing nothing about whether an account/email exists. -/
# +obfuscated We could not complete that request. Please check the details you entered and try again.
