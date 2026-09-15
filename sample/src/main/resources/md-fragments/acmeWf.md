/- Acme's workflow copy (issue #533): a BACKEND fragment file, never served, pulled by the creation
   workflow's labels with %{@t("acmeWf.identify.label")}. The namespace is the task; the keys are what the
   task's page shows. A workflow's own label (issue #719) -- the page's title -- sits under a namespace named
   for the workflow. -/

# @createForm

# +label New expense form

# @reviewForm

# +label Expense report review

# @identify

# +label Answer an issues question and enter your expense report
# +save Create expense form

/- The survey workflow's tasks (issue #656): the owner revisits the same data after creation. Two tasks, one
   namespace each -- the review of the two form traits, and the supplied-defaults profile (issue #711). -/

# @details

# +label Review your expense report and questionnaire
# +save Save changes

# @profile

# +label Confirm your contact details
# +save Save changes
