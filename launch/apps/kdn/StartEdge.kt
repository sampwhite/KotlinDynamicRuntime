package kdn

import com.dynamicruntime.common.http.server.HttpServer

/**
 * The **edge** launcher (issues #347, #377): boots a KDR node running as the `edge` role, on its own port,
 * beside an ordinary one on the same machine.
 *
 * Today it differs from [main] in `Start.kt` by exactly two things -- its role and its default port -- and that
 * is deliberate for a first slice. An edge *is* a KDR node: it serves the same endpoints and the same webapp,
 * and what it will grow is a diverting front door, not a different runtime. Proxying, Env Auth and the
 * `KdrEdge` component follow as their own pieces of work; none of them change this file's shape.
 *
 * Run it with `bin/kdr-edge`.
 */
object EdgeRole {
    /**
     * The role name, which is also the environment-variable namespace: with this set, a lookup for `KDR_PORT`
     * tries `KDR_EDGE_PORT` first. A literal here rather than configuration, because the role must be settled
     * before any application config exists -- and because the launcher is the role.
     */
    const val name = "edge"

    /**
     * The port an edge binds when nothing names one. `8010`, with `8011` for a second (agent's) edge -- the
     * same role-then-instance scheme the application band uses at `7070`/`7071`, in a band of its own.
     *
     * **Chosen for how far it is from `7070`, not for being tidy.** The nearby `7080` was the other candidate
     * and reads better in a list, but it differs from the application port by one character in the middle --
     * and the whole point of a separate port is knowing which server answered. Getting it wrong does not
     * error: it returns a plausible answer from a server nobody meant to ask, which is the failure
     * `kdr-probe`'s design is written against and the one its hardcoded default already caused once. The
     * moment it would bite hardest is the next several slices, spent comparing "through the edge" against
     * "direct to the app" with both ports side by side.
     *
     * There is no external convention to copy: a production reverse proxy binds 80 and 443, and only its
     * management interface has a conventional port. Note `80xx` is otherwise the most crowded range on a
     * development machine -- 8000, 8008, 8080, 8081, 8443, and 8009 for Tomcat's AJP -- so `8010` is a quiet
     * pocket in a busy neighborhood rather than open ground.
     *
     * Deliberately *not* chosen to signal "a different kind of program", because it is not one: an edge shares
     * this launcher's boot sequence, runtime, endpoints and webapp, and a number disagreeing with the
     * structure is a quiet permanent tax.
     */
    const val defaultPort = 8010
}

fun main() {
    HttpServer.launch(bootInstance("startEdge", EdgeRole.name, EdgeRole.defaultPort))
}
