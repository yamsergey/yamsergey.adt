package io.yamsergey.adt.layoutinspector.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Interface for communicating with an inspector agent running on a device.
 * This is modeled after Android Studio's AppInspectorMessenger.
 */
interface InspectorMessenger {
    /**
     * Send a raw command to the inspector and receive a response.
     *
     * @param rawData The serialized command bytes
     * @return The serialized response bytes
     */
    suspend fun sendRawCommand(rawData: ByteArray): ByteArray

    /**
     * Flow of events streamed from the inspector.
     */
    val eventFlow: Flow<ByteArray>

    /**
     * The coroutine scope for this messenger.
     */
    val scope: CoroutineScope
}

/**
 * Exception thrown when inspector communication fails.
 */
class InspectorConnectionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Exception thrown when the inspector crashes.
 */
class InspectorCrashException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
