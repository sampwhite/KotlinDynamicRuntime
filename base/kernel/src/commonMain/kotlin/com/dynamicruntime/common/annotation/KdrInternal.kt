package com.dynamicruntime.common.annotation

/**
 * Marks a declaration that should be *treated as if it were* `internal`, even though it is left with
 * open (public) visibility.
 *
 * The real `internal` keyword is the default for a declaration whose reach is limited to its module. This marker
 * is for the exception: a declaration that would be `internal` but has to be reachable across module boundaries
 * in a way with limited logical implications -- most often so a unit test in another module can reach it. The
 * member stays accessible, and a reader is signaled that it is conceptually internal to its owning module /
 * component and should not be referenced by unrelated code.
 *
 * Use sparingly.
 *
 * See [KdrPrivate] for the class scoped counterpart.
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
annotation class KdrInternal
