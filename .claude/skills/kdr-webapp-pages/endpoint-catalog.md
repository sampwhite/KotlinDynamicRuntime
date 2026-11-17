## The endpoint catalog page

The page at the menu's **Endpoint catalog** (`EndpointCatalog.kt`): every endpoint the caller may call, from
`GET /schema/endpoints`, in a table; one selected opens its schemas and a form that calls it. An endpoint's description
is Markdown whose links resolve as form copy's (issue #1476), so a link to a reference opens it in the app.

**How the form sends a call** follows the endpoint (`SchemaCatalogApi.invoke`): a GET or DELETE in the query string, a
POST or PUT as JSON, an endpoint with a file field as multipart -- except one that takes its body raw (`rawBody` in its
served form, issue #1836), whose picked file is sent as the whole body under `application/octet-stream` and whose other
fields ride in the query string. A download is handed to the browser by its address.

**Its copy file** (`catalog.md`, `PFRAG.catalog`, issue #1633), named by the shell's config as the other page files are,
holds the explanation the table's heading offers (`endpointList`): what is listed, and links to [Using the
API](../../../docs/api-guide.md) and [the roles and access reference](../../../docs/reference/roles-and-access.md), each
by its `/docs/...` path. The heading is a level-1 `WhatIsThis` on the table only; an open endpoint's heading has none.
`HomeUiConfigEndpointTest` holds both links to served documents. The page's other words are still in the webapp.
