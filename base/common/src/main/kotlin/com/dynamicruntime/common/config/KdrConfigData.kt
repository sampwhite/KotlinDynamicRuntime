package com.dynamicruntime.common.config

import com.dynamicruntime.common.context.KdrCxt

/** Base class for configuration builders. */
open class KdrConfigData(val cxt: KdrCxt, val data: MutableMap<String,Any?> = LinkedHashMap()): Map<String,Any?> by data