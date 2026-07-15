package com.dynamicruntime.common.config

import com.dynamicruntime.common.context.KdrCxtBase

/** Base class for configuration builders. */
open class KdrConfigData(val cxt: KdrCxtBase, val data: MutableMap<String,Any?> = LinkedHashMap()): Map<String,Any?> by data