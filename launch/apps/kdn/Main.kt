package kdn

import com.dynamicruntime.common.Common
import com.dynamicruntime.kdn.Kdn

/** Entry point for the KotlinDynamicRuntime launcher. */
fun main() {
    println(Common.greeting())
    println(Kdn.describe())
}
