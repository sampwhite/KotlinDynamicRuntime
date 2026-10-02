package com.dynamicruntime.webapp

/**
 * The outcome of an API call as a value (issue #967): what the server sent ([Ok]), the server's refusal
 * ([Refused] -- a non-2xx envelope, as the [ApiError] [Http] has always raised), or no usable answer at all
 * ([Failed] -- the request never completed, or what came back could not be read).
 *
 * The point is what is **not** here. A call site that reads an `ApiResult` instead of catching gets the two
 * failures it expects, told apart -- a 403 is not a dropped connection -- and nothing else: a bug in the code
 * handling a response, and a coroutine's cancellation, still propagate. A `runCatching` around the same call
 * caught all of them and turned them into one default, so a null dereference read as "the server could not be
 * reached" and a cancelled fetch carried on as though it had merely failed.
 */
sealed interface ApiResult<out T> {
    class Ok<out T>(val value: T) : ApiResult<T>

    /** The server answered and said no; [error] carries its status, code and message. */
    class Refused(val error: ApiError) : ApiResult<Nothing>

    /** No usable answer: [cause] says why. */
    class Failed(val cause: ApiFailure) : ApiResult<Nothing>

    /** The value, or null for either failure. */
    fun valueOrNull(): T? = (this as? Ok)?.value

    /** What went wrong -- the [ApiError] or the [ApiFailure] -- or null when the call succeeded. */
    fun failureOrNull(): Throwable? = when (this) {
        is Ok -> null
        is Refused -> error
        is Failed -> cause
    }

    /** The value, or the failure thrown -- the [ApiError] or the [ApiFailure] it is. */
    fun orThrow(): T = when (this) {
        is Ok -> value
        is Refused -> throw error
        is Failed -> throw cause
    }
}

/** [transform] applied to an [ApiResult.Ok]'s value; a failure is passed through. A throw in [transform] propagates. */
inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Refused -> this
    is ApiResult.Failed -> this
}

/** The value, or [default] for either failure. */
fun <T> ApiResult<T>.valueOr(default: T): T = if (this is ApiResult.Ok) value else default

/**
 * An API call that got no usable answer (issue #967): the request could not be made or completed (the browser's
 * `fetch` rejected -- offline, a reset connection, a blocked request), or a successful response could not be
 * read. The thrown form of [ApiResult.Failed], raised by [Http]'s throwing calls; [cause] is what the browser or
 * the reader reported.
 */
class ApiFailure(message: String, cause: Throwable? = null) : Throwable(message, cause)

/**
 * Runs [block] -- one API call, or several that stand or fall together -- and returns its outcome as an
 * [ApiResult] (issue #967). Only the two failures an API call is expected to have become values: the server's
 * refusal ([ApiError]) and no usable answer ([ApiFailure]). Anything else [block] throws -- a bug in mapping a
 * response, a coroutine's cancellation -- propagates, which is the difference from the `runCatching` this
 * replaces.
 *
 * The adapter between the `*Api` objects, which keep their throwing calls, and a call site that wants a value.
 * Inline, so [block] may suspend.
 */
inline fun <T> apiResult(block: () -> T): ApiResult<T> =
    try {
        ApiResult.Ok(block())
    } catch (e: ApiError) {
        ApiResult.Refused(e)
    } catch (e: ApiFailure) {
        ApiResult.Failed(e)
    }
