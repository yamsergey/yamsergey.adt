package io.yamsergey.adt.layoutinspector.transport

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Helper class for ADB operations using ddmlib.
 */
class AdbHelper(
    private val adbPath: String? = null,
    private val timeoutMs: Long = 30_000
) {
    private var bridge: AndroidDebugBridge? = null

    /**
     * Initialize the ADB bridge.
     */
    fun initialize() {
        val resolvedAdbPath = adbPath ?: findAdbPath()
        if (resolvedAdbPath == null) {
            throw IllegalStateException(
                "ADB not found. Please ensure ANDROID_HOME or ANDROID_SDK_ROOT is set, " +
                "or adb is in PATH"
            )
        }

        // Initialize with client support enabled (true) to track debuggable processes
        AndroidDebugBridge.initIfNeeded(true)
        bridge = AndroidDebugBridge.createBridge(resolvedAdbPath, false)
    }

    /**
     * Find ADB path from environment.
     */
    private fun findAdbPath(): String? {
        // Check ANDROID_HOME
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (androidHome != null) {
            val adbFile = java.io.File(androidHome, "platform-tools/adb")
            if (adbFile.exists() && adbFile.canExecute()) {
                return adbFile.absolutePath
            }
        }

        // Check if adb is in PATH
        val pathDirs = System.getenv("PATH")?.split(java.io.File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val adbFile = java.io.File(dir, "adb")
            if (adbFile.exists() && adbFile.canExecute()) {
                return adbFile.absolutePath
            }
        }

        return null
    }

    /**
     * Wait for the ADB bridge to be connected.
     */
    suspend fun waitForConnection(): Boolean {
        val bridge = this.bridge ?: return false
        return withTimeout(timeoutMs) {
            while (!bridge.isConnected) {
                delay(100)
            }
            // Wait for device list to be populated
            delay(500)
            while (!bridge.hasInitialDeviceList()) {
                delay(100)
            }
            // Wait for JDWP clients to be discovered (debuggable apps)
            // This can take several seconds after ADB daemon starts
            delay(3000)
            true
        }
    }

    /**
     * Wait for JDWP clients to be discovered on a device.
     * Returns true if at least one client is found within timeout.
     */
    suspend fun waitForClients(device: IDevice, maxWaitMs: Long = 10_000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (device.clients.isNotEmpty()) {
                return true
            }
            delay(500)
        }
        return device.clients.isNotEmpty()
    }

    /**
     * Get all connected devices.
     */
    fun getDevices(): List<IDevice> {
        return bridge?.devices?.toList() ?: emptyList()
    }

    /**
     * Get a device by serial number.
     */
    fun getDevice(serial: String): IDevice? {
        return getDevices().find { it.serialNumber == serial }
    }

    /**
     * Get the first available device, optionally filtering by serial.
     */
    fun getTargetDevice(serial: String? = null): IDevice? {
        val devices = getDevices()
        return if (serial != null) {
            devices.find { it.serialNumber == serial }
        } else {
            devices.firstOrNull { it.state == IDevice.DeviceState.ONLINE }
        }
    }

    /**
     * Execute a shell command on a device.
     */
    fun executeShellCommand(
        device: IDevice,
        command: String,
        receiver: com.android.ddmlib.IShellOutputReceiver,
        timeout: Long = timeoutMs,
        timeUnit: TimeUnit = TimeUnit.MILLISECONDS
    ) {
        device.executeShellCommand(command, receiver, timeout, timeUnit)
    }

    /**
     * Push a file to device.
     */
    fun pushFile(device: IDevice, localPath: String, remotePath: String) {
        device.pushFile(localPath, remotePath)
    }

    /**
     * Pull a file from device.
     */
    fun pullFile(device: IDevice, remotePath: String, localPath: String) {
        device.pullFile(remotePath, localPath)
    }

    /**
     * Create a port forward.
     */
    fun createForward(device: IDevice, localPort: Int, remoteSocketName: String) {
        device.createForward(localPort, remoteSocketName, IDevice.DeviceUnixSocketNamespace.ABSTRACT)
    }

    /**
     * Remove a port forward.
     */
    fun removeForward(device: IDevice, localPort: Int) {
        device.removeForward(localPort)
    }

    /**
     * Get device API level.
     */
    fun getApiLevel(device: IDevice): Int {
        val prop = device.getProperty(IDevice.PROP_BUILD_API_LEVEL)
        return prop?.toIntOrNull() ?: 0
    }

    /**
     * Get device ABI.
     */
    fun getAbi(device: IDevice): String {
        return device.getProperty(IDevice.PROP_DEVICE_CPU_ABI) ?: "arm64-v8a"
    }

    /**
     * Check if device is online.
     */
    fun isOnline(device: IDevice): Boolean {
        return device.state == IDevice.DeviceState.ONLINE
    }

    /**
     * List running processes on device.
     */
    suspend fun listProcesses(device: IDevice): List<ProcessInfo> {
        val output = StringBuilder()
        val receiver = object : com.android.ddmlib.MultiLineReceiver() {
            override fun processNewLines(lines: Array<out String>) {
                lines.forEach { output.appendLine(it) }
            }
            override fun isCancelled(): Boolean = false
        }

        executeShellCommand(device, "ps -A -o PID,NAME", receiver)

        return output.toString().lines()
            .drop(1) // Skip header
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    val pid = parts[0].toIntOrNull()
                    val name = parts[1]
                    if (pid != null) ProcessInfo(pid, name) else null
                } else null
            }
    }

    /**
     * Find a process by package name.
     */
    suspend fun findProcess(device: IDevice, packageName: String): ProcessInfo? {
        return listProcesses(device).find { it.name == packageName }
    }

    /**
     * Terminate the ADB bridge.
     */
    fun terminate() {
        AndroidDebugBridge.disconnectBridge()
        AndroidDebugBridge.terminate()
    }

    data class ProcessInfo(val pid: Int, val name: String)
}
