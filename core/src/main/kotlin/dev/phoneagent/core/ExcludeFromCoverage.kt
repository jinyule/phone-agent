package dev.phoneagent.core

/**
 * Excludes the annotated declaration from Kover coverage verification.
 *
 * Use it only for code that cannot be exercised meaningfully on the JVM. [reason] must be a
 * non-blank string literal saying why; `ExcludeFromCoverageUsageTest` rejects anything else.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
public annotation class ExcludeFromCoverage(
    /** Why this code is not covered by tests; reviewed like any other code. */
    val reason: String,
)
