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
     * The port an edge binds when nothing names one.
     *
     * `7080` on a scheme where the tens digit is the role and the ones digit is whose instance: `7070`/`7071`
     * for the application role (a developer's, an agent's), `7080`/`7081` for the edge. There is no external
     * convention to copy -- a production reverse proxy binds 80 and 443, and only its management interface has
     * a conventional port -- so the convention worth having is an internal one that keeps the family
     * recognizable. It also stays out of `80xx`, the most crowded range on a development machine.
     */
    const val defaultPort = 7080
}

fun main() {
    HttpServer.launch(bootInstance("startEdge", EdgeRole.name, EdgeRole.defaultPort))
}
