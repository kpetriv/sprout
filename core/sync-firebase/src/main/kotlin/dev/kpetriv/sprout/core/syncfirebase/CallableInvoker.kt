package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam between [HouseholdFunctions] and the Functions SDK: calls one callable with plain data (maps, lists,
 * strings, numbers, booleans, null) and returns the plain response. Failures throw [FunctionsException] only.
 */
interface CallableInvoker {
    suspend fun call(name: String, data: Map<String, Any?>, timeoutMs: Long): Any?
}

/** A failed callable. The message is the [FunctionsError] code; the server's message text is never kept. */
class FunctionsException(val error: FunctionsError) : Exception(error.code)

/** The HTTPS callable error of a failed call (M3 §1). Codes only, no message text. */
sealed class FunctionsError(val code: String) {
    data object Unauthenticated : FunctionsError("unauthenticated")

    data object PermissionDenied : FunctionsError("permission-denied")

    data object NotFound : FunctionsError("not-found")

    data object AlreadyExists : FunctionsError("already-exists")

    data object InvalidArgument : FunctionsError("invalid-argument")

    /** Rate limit or quota (too many link codes, too many attempts, export too large). */
    data object ResourceExhausted : FunctionsError("resource-exhausted")

    /** The call is not possible in the current state (no challenge outstanding, device not pending, App Check). */
    data object FailedPrecondition : FunctionsError("failed-precondition")

    /** Network trouble or a timeout; the call may be retried. */
    data object Unavailable : FunctionsError("unavailable")

    /** Any other code (lower-case gRPC name), or `malformed-response` when the answer did not decode. */
    data class Unknown(val raw: String) : FunctionsError(raw)

    companion object {
        /** Maps a gRPC status name (`PERMISSION_DENIED`, as in `FirebaseFunctionsException.Code`) to an error. */
        fun fromCodeName(name: String): FunctionsError = when (name) {
            "UNAUTHENTICATED" -> Unauthenticated
            "PERMISSION_DENIED" -> PermissionDenied
            "NOT_FOUND" -> NotFound
            "ALREADY_EXISTS" -> AlreadyExists
            "INVALID_ARGUMENT", "OUT_OF_RANGE" -> InvalidArgument
            "RESOURCE_EXHAUSTED" -> ResourceExhausted
            "FAILED_PRECONDITION" -> FailedPrecondition
            "UNAVAILABLE", "DEADLINE_EXCEEDED" -> Unavailable
            else -> Unknown(name.lowercase().replace('_', '-'))
        }

        val MalformedResponse: FunctionsError = Unknown("malformed-response")
    }
}

/** [CallableInvoker] over `FirebaseFunctions.getInstance(app, region)`. */
@Singleton
class FirebaseCallableInvoker @Inject constructor(
    private val bootstrap: FirebaseBootstrap,
    private val config: FirebaseConfig,
    @param:ApplicationContext private val context: Context,
) : CallableInvoker {
    private val functions: FirebaseFunctions by lazy {
        FirebaseFunctions.getInstance(bootstrap.ensureInitialised(context), config.region)
    }

    @Suppress("SwallowedException") // The SDK exception's message may echo request data; only the code is kept.
    override suspend fun call(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? = try {
        val callable = functions.getHttpsCallable(name)
        callable.setTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        callable.call(data).await().getData()
    } catch (failure: FirebaseFunctionsException) {
        throw FunctionsException(FunctionsError.fromCodeName(failure.code.name))
    } catch (failure: FirebaseNetworkException) {
        throw FunctionsException(FunctionsError.Unavailable)
    }
}
