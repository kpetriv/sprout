package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam between [HouseholdFunctions] and the Functions SDK: calls one callable with plain data (maps, lists,
 * strings, numbers, booleans, null) and returns the plain response. Failures throw [FunctionsException] only.
 */
interface CallableInvoker {
    /** Calls [name] with the signed-in user's ID token (none when signed out) and App Check, through the SDK. */
    suspend fun call(name: String, data: Map<String, Any?>, timeoutMs: Long): Any?

    /**
     * Calls [name] without any ID token: works signed out and with a session that can no longer refresh (a disabled
     * or deleted user), which [call] cannot (the SDK fails the call when the token read fails). Only for the callable
     * the server declares token-less (`deviceStatus`). The default, for fakes, is [call].
     */
    suspend fun callWithoutAuth(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? =
        call(name, data, timeoutMs)
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

/**
 * [CallableInvoker] over `FirebaseFunctions.getInstance(app, regionOrCustomDomain)`: the custom domain
 * [FirebaseConfig.functionsBaseUrl] when set (D-M3-29: an HTTP service speaking the callable protocol), else Cloud
 * Functions in [FirebaseConfig.region] ([FirebaseConfig.functionsEndpoint]).
 */
@Singleton
class FirebaseCallableInvoker @Inject constructor(
    private val bootstrap: FirebaseBootstrap,
    private val config: FirebaseConfig,
    @param:ApplicationContext private val context: Context,
) : CallableInvoker {
    private val functions: FirebaseFunctions by lazy {
        FirebaseFunctions.getInstance(bootstrap.ensureInitialised(context), config.functionsEndpoint.value)
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

    /**
     * The callable protocol by hand ([CallableHttp]): `POST <endpoint>/<name>` with `{"data": ...}`, the App Check
     * token when one can be had (the server requires it outside the emulator), and no `Authorization` header.
     */
    override suspend fun callWithoutAuth(name: String, data: Map<String, Any?>, timeoutMs: Long): Any? {
        val app = bootstrap.ensureInitialised(context)
        val url = CallableHttp.url(config.functionsEndpoint, app.options.projectId, name)
        return CallableHttp.post(url, data, appCheckToken(), timeoutMs)
    }

    /** The App Check token, or null when there is none (no provider installed, attestation failed, offline). */
    @Suppress("SwallowedException") // No token is not an error here: the server answers without one in the emulator.
    private suspend fun appCheckToken(): String? = try {
        FirebaseAppCheck.getInstance(bootstrap.ensureInitialised(context)).getAppCheckToken(false).await().token
    } catch (failure: FirebaseException) {
        null
    }
}

/**
 * The Firebase callable protocol over [HttpURLConnection] for [CallableInvoker.callWithoutAuth]
 * (https://firebase.google.com/docs/functions/callable-reference). Answers are `{"result": ...}` (returned as plain
 * values) or `{"error": {"status": ...}}` (thrown as [FunctionsException] with that status; the message is dropped).
 */
internal object CallableHttp {
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val BUFFER_BYTES = 4 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    /** `<base>/<name>` for a custom domain (D-M3-29), else the Cloud Functions URL of [projectId] in its region. */
    fun url(endpoint: FunctionsEndpoint, projectId: String?, name: String): String = when (endpoint) {
        is FunctionsEndpoint.CustomDomain -> "${endpoint.value}/$name"
        is FunctionsEndpoint.Region -> "https://${endpoint.value}-$projectId.cloudfunctions.net/$name"
    }

    @Suppress("SwallowedException") // Network trouble is Unavailable; the exception's message names the host.
    suspend fun post(url: String, data: Map<String, Any?>, appCheckToken: String?, timeoutMs: Long): Any? =
        withContext(Dispatchers.IO) {
            try {
                exchange(url, data, appCheckToken, timeoutMs)
            } catch (unreachable: IOException) {
                throw FunctionsException(FunctionsError.Unavailable)
            }
        }

    private fun exchange(url: String, data: Map<String, Any?>, appCheckToken: String?, timeoutMs: Long): Any? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs.toInt()
            connection.readTimeout = timeoutMs.toInt()
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            appCheckToken?.let { connection.setRequestProperty("X-Firebase-AppCheck", it) }
            val body = JsonObject(mapOf("data" to PlainValues.toJson(data)))
            connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
            val status = connection.responseCode
            val stream = (if (status in HTTP_OK_RANGE) connection.inputStream else connection.errorStream)
                ?: throw FunctionsException(FunctionsError.MalformedResponse)
            return parse(stream.use(::readBounded).decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    /** At most [MAX_RESPONSE_BYTES] of [stream]; a longer answer is malformed (deviceStatus answers are tiny). */
    private fun readBounded(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_RESPONSE_BYTES) throw FunctionsException(FunctionsError.MalformedResponse)
            out.write(buffer, 0, read)
        }
    }

    /** The plain `result`, or the [FunctionsException] of `error.status`. */
    fun parse(text: String): Any? {
        val body = parseObject(text)
        val error = body?.get("error")
        val failure = when {
            body == null -> FunctionsError.MalformedResponse
            error != null -> statusOf(error)?.let(FunctionsError::fromCodeName) ?: FunctionsError.MalformedResponse
            "result" !in body -> FunctionsError.MalformedResponse
            else -> return PlainValues.toPlain(body.getValue("result"))
        }
        throw FunctionsException(failure)
    }

    @Suppress("SwallowedException") // The body may echo request data; only the outcome is kept.
    private fun parseObject(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (malformed: SerializationException) {
        null
    }

    private fun statusOf(error: JsonElement): String? =
        ((error as? JsonObject)?.get("status") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val HTTP_OK_RANGE = 200..299
}
