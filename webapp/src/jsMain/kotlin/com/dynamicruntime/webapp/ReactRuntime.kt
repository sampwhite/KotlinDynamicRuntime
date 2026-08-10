@file:JsModule("react")
@file:JsNonModule

package com.dynamicruntime.webapp

/**
 * The two React primitives the kotlin-wrappers do not surface, declared the way "AntdComponents.kt" declares
 * antd's: `@file:JsModule("react")` maps each `external` below to a named export of the `react` package.
 *
 * They exist for exactly one reason. An **error boundary must be a class component** -- React decides whether
 * a component can catch by looking for `getDerivedStateFromError` / `componentDidCatch` on it, and a function
 * component can carry neither. The wrappers are function-component oriented and expose no base class, so the
 * boundary is built in a small `js()` block (see `ErrorBoundary.kt`) that needs [Component] to extend and
 * [createElement] to render its fallback. Nothing else in the app should reach for these.
 */
external val Component: dynamic

/** React's element factory, used only by the boundary to render a Kotlin fallback component from raw JS. */
external fun createElement(type: dynamic, props: dynamic): dynamic
