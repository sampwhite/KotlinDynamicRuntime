package kdn

import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.http.server.HttpServer

/**
 * The **edge** launcher (issues #347, #377): boots a KDR node running as the `edge` role, on its own port,
 * beside an ordinary one on the same machine.
 *
 * It differs from [main] in `Start.kt` by two values and nothing else: the role it declares, and the component
 * it requires -- both named in `BOOT`, so this file references no edge code. Even the port is absent: the
 * component contributes it through `applyInstanceConfig`. An edge *is* a KDR node with the same runtime and the
 * same boot sequence; what it grows is a diverting front door, not a different program, which is why this
 * stays this short. Proxying will not change its shape either.
 *
 * Run it with `bin/kdr-edge`.
 */
fun main() {
    // KdrEdge arrives by ServiceLoader discovery, exactly as `sample` does -- this file never names it, so a
    // workspace without `:edge` in its settings still builds. It is *required* rather than optional, so a
    // launcher that cannot find it refuses to start instead of quietly serving an ordinary node.
    HttpServer.launch(
        bootInstance("startEdge", BOOT.edge, requiredComponents = listOf(BOOT.edgeComponent)),
    )
}
