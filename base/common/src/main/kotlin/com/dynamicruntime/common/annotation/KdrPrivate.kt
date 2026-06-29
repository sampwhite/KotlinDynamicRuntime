package com.dynamicruntime.common.annotation

/**
 * Marks a declaration that should be *treated as if it were* `private`, even though it is left with
 * open (public) visibility.
 *
 * This is the annotation form of the code-guide rule that we minimize use of the `private` keyword.
 * The member stays accessible -- which keeps it testable and reachable by the dynamic / plugin parts
 * of the code base -- but a reader is signalled that it is conceptually private to its enclosing
 * class and should not be referenced from outside that class.
 *
 * Use sparingly. Where actual enforcement genuinely matters, prefer the real `private` keyword -- for
 * example a cache that is mutated without first synchronizing and must never be touched from outside
 * its owner.
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
