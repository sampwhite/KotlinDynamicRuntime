package com.dynamicruntime.common.annotation

/**
 * Marks a declaration that should be *treated as if it were* `private`, even though it is left with
 * open (public) visibility.
 *
 * The real `private` keyword is the default for a declaration whose reach is limited to its class. This marker
 * is for the exception: a declaration that would be `private` but has to be reachable from elsewhere in a way
 * with limited logical implications -- most often so a unit test can reach it. The member stays accessible, and a
 * reader is signaled that it is conceptually private to its enclosing class and should not be referenced from
 * outside that class.
 *
 * Use sparingly.
 *
 * See [KdrInternal] for the module / component scoped counterpart.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
annotation class KdrPrivate
