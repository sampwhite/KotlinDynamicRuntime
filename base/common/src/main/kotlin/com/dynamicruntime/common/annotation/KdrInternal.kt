package com.dynamicruntime.common.annotation

/**
 * Marks a declaration that should be *treated as if it were* `internal`, even though it is left with
 * open (public) visibility.
 *
 * This is the annotation form of the code-guide rule that we minimize use of the `internal` keyword.
 * The member stays accessible across module boundaries -- which keeps it testable and reachable by the
 * dynamic / plugin parts of the code base -- but a reader is signalled that it is conceptually internal
 * to its owning module / component and should not be referenced by unrelated code.
 *
 * Use sparingly. Where actual enforcement genuinely matters, prefer the real `internal` keyword.
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
