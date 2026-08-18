package com.dynamicruntime.edge

/**
 * What is genuinely the **edge** boot role's own (issues #377, #386).
 *
 * The role *name* is `BOOT.edge`, in `base/common`, because endpoints and services will be profiled by role
 * and those declarations are spread across every module -- they could not all depend on this one. Common
 * learning that `edge` is a possible role is not common learning what a `KdrEdge` is. The port is not
 * vocabulary, so it stays here, and reaches the runtime through
 * `EdgeComponent.applyInstanceConfig` -- early enough that `NodeService` sees it when it fixes node identity.
 */
@Suppress("ConstPropertyName")
object EdgeRole {
    /**
     * The port an edge binds when nothing names one, with `8011` free for a second (agent's) edge -- the same
     * role-then-instance scheme the application band uses at `7070`/`7071`, in a band of its own.
     *
     * **Chosen for how far it is from `7070`, not for being tidy.** `7080` reads better in a list but differs
     * by one character in the middle, and the whole point of a separate port is knowing which server answered.
     * Getting it wrong does not error: it returns a plausible answer from a server nobody meant to ask, which
     * is the failure `kdr-probe`'s design is written against and the one its hardcoded default already caused
     * here. It would bite hardest during exactly the work that follows, spent comparing "through the edge"
     * against "direct to the app" with both ports side by side.
     *
     * There is no external convention to copy: a production reverse proxy binds 80 and 443, and only its
     * management interface has a conventional port. Note `80xx` is otherwise the most crowded range on a
     * development machine -- 8000, 8008, 8080, 8081, 8443, and 8009 for Tomcat's AJP -- so `8010` is a quiet
     * pocket in a busy neighborhood rather than open ground.
     *
     * Deliberately *not* chosen to signal "a different kind of program": it is not one, and a number that
     * disagrees with the structure is a quiet permanent tax.
     */
    const val defaultPort = 8010
}
