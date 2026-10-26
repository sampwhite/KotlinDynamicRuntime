/- Copy for Design View (issue #1337): for now, the "What is this?" explanation of a workflow's own copy against the
   shared definition's. Design View's other words are still in the webapp, and belong here as they are moved. Named
   by the shell's UI-config after the home file, since the view has no UI-config of its own. -/

# @ownCopy
# +whatIsThis
A field's copy (its label, description and hint) and a section's heading come from its **shared** definition: every
workflow that shows the field shows the same words.

A workflow can have **its own** copy instead, which only its pages show while every other workflow keeps the shared
words. Edit it here, and **Reset to shared** goes back to the shared words. To change the words in every workflow,
edit the shared definition. Wording pulled from a copy file is changed at its key, and so everywhere that key is
used.
