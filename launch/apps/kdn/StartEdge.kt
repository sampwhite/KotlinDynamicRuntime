package kdn

import com.dynamicruntime.common.http.server.HttpServer
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.edge.EdgeComponent
import com.dynamicruntime.edge.EdgeRole

/**
 * The **edge** launcher (issues #347, #377): boots a KDR node running as the `edge` role, on its own port,
 * beside an ordinary one on the same machine.
 *
 * It differs from [main] in `Start.kt` by three things: its role, its default port, and the `KdrEdge` component
 * it registers. An edge *is* a KDR node -- same runtime, same boot sequence -- and what it grows is a diverting
 * front door rather than a different program, which is why this file stays this short. Proxying follows as its
 * own piece of work and will not change its shape either.
 *
 * Run it with `bin/kdr-edge`.
 */
fun main() {
    // The one place that may name KdrEdge. `launch` is not a component, and knowing which role it boots is
    // exactly its job -- see the module's build file for why nothing else may depend on it.
    InstanceRegistry.register(listOf(EdgeComponent()))
    HttpServer.launch(bootInstance("startEdge", EdgeRole.name, EdgeRole.defaultPort))
}
