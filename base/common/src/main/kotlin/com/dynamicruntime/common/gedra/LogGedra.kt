package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.logging.KdrLogger

/**
 * Topic logger for the gedra subsystem -- storage, state, and the derivations that compute it. Lives beside
 * the code it serves, as the topic loggers do; the `"gedra"` topic is owned by this subsystem.
 */
object LogGedra : KdrLogger("gedra")
