package com.dynamicruntime.webapp

import react.FC
import react.Props

/**
 * The application root: the schema-driven endpoint browser ([EndpointCatalog]). The earlier React/antd demo
 * controls (counter, greeting, sample antd widgets) were removed once the display engine took over the page.
 */
val App = FC<Props> {
    EndpointCatalog {}
}
