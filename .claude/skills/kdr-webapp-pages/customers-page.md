## The Customers page (issue #1114)

`/wa/customers` (`CustomersPage.kt`, its pure half in `CustomersApi.kt`, covered by `CustomersPageTest`), grown out
of the Clients page. An administrator who sees every client gets the deployment's customers
(`GET /clientAdmin/customers`), each opening to its page (`cu=<id>`, `customerHref`); an administrator of a customer's
**root** client, client-wide, is shown their own customer in place (`GET /clientAdmin/customer/overview` with no
`customer`), with no way back, as a client's administrator is shown their client (#1082). The menu offers "Customers"
to the first and "My customer" to the second (`HMENU.customers`, `HMENU.myCustomer`; the `administersCustomer` cfact).

A customer's page says what it is -- name, plan, root client, and where it is declared -- and lists its clients, sandboxes
aside, as the Clients overview's rows (`parseClientOverview`): load and issues on hover, counts, plan usage, and what
it runs (`clientRunsText`). **Read-only, facts only.** Every row links to its client page for an administrator who
sees every client; for a root's administrator only their own client does (`customerClientLinked`), since each edits
only their own. A refusal (403, 404) is shown in the endpoint's words. The way up runs too: a client page's "Customer
id" row opens the customer's page where the caller may open it (`customerSummaryHref`) -- always for a deployment
administrator, and on the root client's page (the overview row's `rootOf`) for its administrators -- and is plain
text elsewhere.
