package io.yamsergey.adt.layoutinspector.transport

import com.android.ddmlib.IDevice
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Client for communicating with the Transport daemon on an Android device.
 *
 * The Transport daemon runs on the device and provides:
 * - Process discovery
 * - JVMTI agent attachment
 * - Inspector deployment and communication
 */
class TransportClient(
    private val adbHelper: AdbHelper,
    private val device: IDevice
) : Closeable {

    companion object {
        const val DEVICE_SOCKET_NAME = "AndroidStudioTransport"
        const val LEGACY_DEVICE_PORT = 12389
        const val DEFAULT_LOCAL_PORT = 12390
    }

    private var localPort: Int = DEFAULT_LOCAL_PORT
    private var channel: ManagedChannel? = null
    private var connected = false

    /**
     * Connect to the Transport daemon on the device.
     * Creates a port forward and establishes gRPC channel.
     */
    suspend fun connect(port: Int = DEFAULT_LOCAL_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            localPort = port

            // Create port forward to the Transport daemon socket
            val apiLevel = adbHelper.getApiLevel(device)
            if (apiLevel >= 26) {
                // API 26+ uses Unix abstract socket
                adbHelper.createForward(device, localPort, DEVICE_SOCKET_NAME)
            } else {
                // Older devices use TCP port
                // Note: This may not work for all devices
                println("Warning: API level $apiLevel may have limited support")
            }

            // Create gRPC channel
            channel = ManagedChannelBuilder.forAddress("localhost", localPort)
                .usePlaintext()
                .maxInboundMessageSize(512 * 1024 * 1024) // 512MB max message
                .build()

            connected = true
            true
        } catch (e: Exception) {
            println("Failed to connect to Transport daemon: ${e.message}")
            false
        }
    }

    /**
     * Check if connected to the Transport daemon.
     */
    fun isConnected(): Boolean = connected && channel != null

    /**
     * Get the gRPC channel for making requests.
     */
    fun getChannel(): ManagedChannel? = channel

    /**
     * Get the local port used for connection.
     */
    fun getLocalPort(): Int = localPort

    /**
     * Disconnect from the Transport daemon.
     */
    override fun close() {
        try {
            channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Ignore shutdown errors
        }
        try {
            adbHelper.removeForward(device, localPort)
        } catch (e: Exception) {
            // Ignore forward removal errors
        }
        connected = false
        channel = null
    }
}

/**
 * Represents a running process on the device that can be inspected.
 */
data class InspectableProcess(
    val pid: Int,
    val name: String,
    val packageName: String,
    val isDebuggable: Boolean
)

/**
 * Exception thrown when Transport operations fail.
 */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
