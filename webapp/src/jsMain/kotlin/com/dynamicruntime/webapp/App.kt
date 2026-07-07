package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

/**
 * The sample UI. A function component (`FC`) written entirely in Kotlin and
 * compiled to JavaScript: a counter demonstrating React state/hooks, and a
 * name field that calls the TypeScript-exported [greet] function from [Api.kt].
 *
 * Styling lives in index.html so this file stays focused on structure/behavior.
 */
val App = FC<Props> {
    var count by useState(0)
    var name by useState("")

    div {
        className = ClassName("card")

        h1 { +"Kotlin → React → TypeScript" }
        p {
            className = ClassName("subtitle")
            +"This entire page is Kotlin compiled to JavaScript and rendered with React."
        }

        // --- Counter: React state via hooks ---
        h2 { +"Counter" }
        div {
            className = ClassName("row")
            button {
                onClick = { count -= 1 }
                +"−"
            }
            span {
                className = ClassName("count")
                +count.toString()
            }
            button {
                onClick = { count += 1 }
                +"+"
            }
            button {
                className = ClassName("ghost")
                onClick = { count = 0 }
                +"reset"
            }
        }

        // --- Greeting: calls the @JsExport Kotlin API that also lands in the .d.ts ---
        h2 { +"Greeting" }
        div {
            className = ClassName("row")
            input {
                placeholder = "your name"
                value = name
                onChange = { event -> name = event.target.value }
            }
        }
        p {
            className = ClassName("greeting")
            +greet(name).message
        }

        // --- Ant Design: npm React components driven from Kotlin ---
        h2 { +"Ant Design" }
        Space {
            Button {
                type = "primary"
                onClick = { count += 1 }
                +"antd primary (+1)"
            }
            Button {
                onClick = { count = 0 }
                +"antd reset"
            }
            DatePicker {
                // antd hands back the formatted string; feed it into the name field.
                onChange = { _, dateString -> name = dateString }
            }
        }
    }

    // The Todo list is a self-contained component with its own card; it talks to
    // the `:sample` runtime's Todo endpoints. Rendered as a sibling card below the demo.
    TodoList {}
}
