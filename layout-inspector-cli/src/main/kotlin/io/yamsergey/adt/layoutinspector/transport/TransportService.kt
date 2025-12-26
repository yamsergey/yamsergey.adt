package io.yamsergey.adt.layoutinspector.transport

import com.android.ddmlib.IDevice
import com.android.tools.profiler.proto.*
import com.android.tools.app.inspection.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * Service for communicating with the Transport daemon on an Android device.
 * This handles deploying the daemon, connecting via gRPC, and managing inspectors.
 */
class TransportService(
    private val adbHelper: AdbHelper,
    private val device: IDevice,
    private val androidStudioPath: String = "/snap/android-studio/current"
) : Closeable {

    companion object {
        const val DEVICE_SOCKET_NAME = "AndroidStudioTransport"
        const val DEFAULT_LOCAL_PORT = 12389
        private const val DEVICE_PORT = 12389
        private const val DEVICE_DIR = "/data/local/tmp/perfd"
        private const val DAEMON_CONFIG_FILE = "daemon.config"
        private const val AGENT_CONFIG_FILE = "agent.config"
        private val commandIdGenerator = AtomicInteger(0)

        fun nextCommandId(): Int = commandIdGenerator.incrementAndGet()
    }

    private var localPort: Int = DEFAULT_LOCAL_PORT
    private var channel: ManagedChannel? = null
    private var stub: TransportServiceGrpc.TransportServiceBlockingStub? = null
    private var asyncStub: TransportServiceGrpc.TransportServiceStub? = null
    private var streamId: Long = 0
    private var sessionId: Long = 0

    // Host-side proxy for event handling
    private var proxy: TransportProxy? = null

    private val abi: String get() = adbHelper.getAbi(device)
    private val apiLevel: Int get() = adbHelper.getApiLevel(device)

    /**
     * Calculate device ID using SHA-256 of bootId + serialNumber.
     * This matches how Android Studio calculates it.
     */
    private fun calculateDeviceId(): Long {
        try {
            // Get boot_id from device
            val receiver = SimpleOutputReceiver()
            device.executeShellCommand("cat /proc/sys/kernel/random/boot_id", receiver, 5, TimeUnit.SECONDS)
            val bootId = receiver.output.trim()

            // Calculate SHA-256(bootId + serial)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(bootId.toByteArray())
            digest.update(device.serialNumber.toByteArray())
            return java.nio.ByteBuffer.wrap(digest.digest()).long
        } catch (e: Exception) {
            println("Warning: Could not calculate device ID: ${e.message}")
            return device.serialNumber.hashCode().toLong()
        }
    }

    private fun getCpuArch(): String = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> "arm64"
    }

    /**
     * Get paths to required files from Android Studio installation.
     */
    private fun getTransportDaemonPath(): String =
        "$androidStudioPath/plugins/android/resources/transport/$abi/transport"

    private fun getAgentPath(): String =
        "$androidStudioPath/plugins/android/resources/transport/native/agent/$abi/libjvmtiagent.so"

    private fun getInspectorJarPath(): String =
        "$androidStudioPath/plugins/android/resources/app-inspection/layoutinspector-view-inspection.jar"

    private fun getPerfaJarPath(): String =
        "$androidStudioPath/plugins/android/resources/perfa.jar"

    // Cache directory for downloaded inspector JARs
    private val inspectorCacheDir: File by lazy {
        File(System.getProperty("user.home"), ".layout-inspector-cli/inspectors").also { it.mkdirs() }
    }

    /**
     * Download and deploy the Compose inspector JAR from Google Maven.
     * @param composeVersion The Compose UI version (e.g., "1.7.0", "1.5.0-beta01")
     * @return The device path where the inspector JAR was deployed, or null if failed
     */
    suspend fun deployComposeInspector(composeVersion: String): String? = withContext(Dispatchers.IO) {
        try {
            println("Deploying Compose inspector for version $composeVersion...")

            // Determine which artifact to use based on version
            // Versions >= 1.5.0-beta01 use ui-android, earlier versions use ui
            val useAndroidArtifact = isVersionAtLeast(composeVersion, "1.5.0-beta01")
            val artifactId = if (useAndroidArtifact) "ui-android" else "ui"
            val groupPath = "androidx/compose/ui"

            println("  Using artifact: $groupPath/$artifactId:$composeVersion")

            // Check cache first
            val cachedJar = File(inspectorCacheDir, "compose-inspector-$composeVersion.jar")
            if (!cachedJar.exists()) {
                // Download the AAR from Google Maven
                val aarUrl = "https://maven.google.com/$groupPath/$artifactId/$composeVersion/$artifactId-$composeVersion.aar"
                println("  Downloading from: $aarUrl")

                val aarBytes = downloadFromMaven(aarUrl)
                if (aarBytes == null) {
                    println("  Failed to download AAR from Maven")
                    return@withContext null
                }
                println("  Downloaded ${aarBytes.size} bytes")

                // Extract inspector.jar from the AAR
                val inspectorBytes = extractInspectorJar(aarBytes)
                if (inspectorBytes == null) {
                    println("  Failed to extract inspector.jar from AAR")
                    return@withContext null
                }
                println("  Extracted inspector.jar: ${inspectorBytes.size} bytes")

                // Cache the extracted JAR
                cachedJar.writeBytes(inspectorBytes)
                println("  Cached to: ${cachedJar.absolutePath}")
            } else {
                println("  Using cached JAR: ${cachedJar.absolutePath}")
            }

            // Push to device
            val devicePath = "$DEVICE_DIR/compose-inspector.jar"
            println("  Pushing to device: $devicePath")
            device.pushFile(cachedJar.absolutePath, devicePath)

            val receiver = SimpleOutputReceiver()
            device.executeShellCommand("chmod 444 $devicePath; chown shell:shell $devicePath", receiver, 10, TimeUnit.SECONDS)

            println("  Compose inspector deployed successfully")
            devicePath
        } catch (e: Exception) {
            println("  Failed to deploy Compose inspector: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Compare version strings to check if version is at least the minimum.
     */
    private fun isVersionAtLeast(version: String, minimum: String): Boolean {
        val vParts = parseVersion(version)
        val mParts = parseVersion(minimum)

        for (i in 0 until maxOf(vParts.size, mParts.size)) {
            val v = vParts.getOrNull(i) ?: 0
            val m = mParts.getOrNull(i) ?: 0
            if (v > m) return true
            if (v < m) return false
        }
        return true
    }

    /**
     * Parse a version string like "1.5.0-beta01" into comparable parts.
     */
    private fun parseVersion(version: String): List<Int> {
        val parts = mutableListOf<Int>()
        // Split by dots and hyphens
        val segments = version.split(".", "-")
        for (segment in segments) {
            // Extract leading number
            val numMatch = Regex("^(\\d+)").find(segment)
            if (numMatch != null) {
                parts.add(numMatch.groupValues[1].toInt())
            }
            // Check for alpha/beta/rc suffix
            when {
                segment.contains("alpha", ignoreCase = true) -> parts.add(-3)
                segment.contains("beta", ignoreCase = true) -> parts.add(-2)
                segment.contains("rc", ignoreCase = true) -> parts.add(-1)
            }
        }
        return parts
    }

    /**
     * Download a file from Google Maven.
     */
    private fun downloadFromMaven(urlString: String): ByteArray? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            if (connection.responseCode == 200) {
                connection.inputStream.use { it.readBytes() }
            } else {
                println("    HTTP ${connection.responseCode}: ${connection.responseMessage}")
                null
            }
        } catch (e: Exception) {
            println("    Download error: ${e.message}")
            null
        }
    }

    /**
     * Extract inspector.jar from an AAR file.
     */
    private fun extractInspectorJar(aarBytes: ByteArray): ByteArray? {
        ZipInputStream(aarBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "inspector.jar") {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /**
     * Deploy transport daemon and agent to the device.
     */
    suspend fun deployToDevice(): Boolean = withContext(Dispatchers.IO) {
        try {
            println("Deploying transport daemon to device...")

            // Create device directory with proper permissions
            val receiver = SimpleOutputReceiver()
            device.executeShellCommand("mkdir -p -m 755 $DEVICE_DIR; chown shell:shell $DEVICE_DIR", receiver, 10, TimeUnit.SECONDS)

            // Push transport daemon
            val transportPath = getTransportDaemonPath()
            if (!File(transportPath).exists()) {
                throw TransportException("Transport daemon not found at: $transportPath")
            }
            println("  Pushing transport daemon...")
            device.pushFile(transportPath, "$DEVICE_DIR/transport")
            device.executeShellCommand("chmod 755 $DEVICE_DIR/transport; chown shell:shell $DEVICE_DIR/transport", receiver, 10, TimeUnit.SECONDS)

            // Push JVMTI agent with arch-specific name
            val agentPath = getAgentPath()
            if (!File(agentPath).exists()) {
                throw TransportException("JVMTI agent not found at: $agentPath")
            }
            val cpuArch = getCpuArch()
            val agentFileName = "libjvmtiagent_$cpuArch.so"
            println("  Pushing JVMTI agent as $agentFileName...")
            device.pushFile(agentPath, "$DEVICE_DIR/$agentFileName")
            device.executeShellCommand("chown shell:shell $DEVICE_DIR/$agentFileName", receiver, 10, TimeUnit.SECONDS)

            // Push perfa.jar (Java profiler agent code)
            val perfaJarPath = getPerfaJarPath()
            if (!File(perfaJarPath).exists()) {
                throw TransportException("Perfa JAR not found at: $perfaJarPath")
            }
            println("  Pushing perfa.jar...")
            device.pushFile(perfaJarPath, "$DEVICE_DIR/perfa.jar")
            device.executeShellCommand("chmod 444 $DEVICE_DIR/perfa.jar; chown shell:shell $DEVICE_DIR/perfa.jar", receiver, 10, TimeUnit.SECONDS)

            // Push inspector JAR
            val jarPath = getInspectorJarPath()
            if (!File(jarPath).exists()) {
                throw TransportException("Inspector JAR not found at: $jarPath")
            }
            println("  Pushing inspector JAR...")
            device.pushFile(jarPath, "$DEVICE_DIR/layoutinspector-view-inspection.jar")
            device.executeShellCommand("chmod 444 $DEVICE_DIR/layoutinspector-view-inspection.jar; chown shell:shell $DEVICE_DIR/layoutinspector-view-inspection.jar", receiver, 10, TimeUnit.SECONDS)

            // Create and push daemon config
            println("  Pushing daemon config...")
            pushDaemonConfig()

            // Create and push agent config
            println("  Pushing agent config...")
            pushAgentConfig()

            println("Deployment complete.")
            true
        } catch (e: Exception) {
            println("Deployment failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Create and push daemon configuration file.
     */
    private fun pushDaemonConfig() {
        val socketType = if (apiLevel >= 26) {
            CommonConfig.SocketType.ABSTRACT_SOCKET
        } else {
            CommonConfig.SocketType.UNSPECIFIED_SOCKET
        }

        val commonConfig = CommonConfig.newBuilder()
            .setSocketType(socketType)
            .setServiceAddress("127.0.0.1:$DEVICE_PORT")
            .setServiceSocketName(DEVICE_SOCKET_NAME)
            .build()

        val daemonConfig = DaemonConfig.newBuilder()
            .setCommon(commonConfig)
            .build()

        // Write config to temp file and push to device
        val tempFile = File.createTempFile("daemon", ".config")
        try {
            tempFile.outputStream().use { daemonConfig.writeTo(it) }
            device.executeShellCommand("rm -f $DEVICE_DIR/$DAEMON_CONFIG_FILE", SimpleOutputReceiver(), 10, TimeUnit.SECONDS)
            device.pushFile(tempFile.absolutePath, "$DEVICE_DIR/$DAEMON_CONFIG_FILE")
            device.executeShellCommand("chown shell:shell $DEVICE_DIR/$DAEMON_CONFIG_FILE", SimpleOutputReceiver(), 10, TimeUnit.SECONDS)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Create and push agent configuration file.
     */
    private fun pushAgentConfig() {
        val socketType = if (apiLevel >= 26) {
            CommonConfig.SocketType.ABSTRACT_SOCKET
        } else {
            CommonConfig.SocketType.UNSPECIFIED_SOCKET
        }

        val commonConfig = CommonConfig.newBuilder()
            .setSocketType(socketType)
            .setServiceAddress("127.0.0.1:$DEVICE_PORT")
            .setServiceSocketName(DEVICE_SOCKET_NAME)
            .build()

        val agentConfig = AgentConfig.newBuilder()
            .setCommon(commonConfig)
            .build()

        // Write config to temp file and push to device
        val tempFile = File.createTempFile("agent", ".config")
        try {
            tempFile.outputStream().use { agentConfig.writeTo(it) }
            device.executeShellCommand("rm -f $DEVICE_DIR/$AGENT_CONFIG_FILE", SimpleOutputReceiver(), 10, TimeUnit.SECONDS)
            device.pushFile(tempFile.absolutePath, "$DEVICE_DIR/$AGENT_CONFIG_FILE")
            device.executeShellCommand("chown shell:shell $DEVICE_DIR/$AGENT_CONFIG_FILE", SimpleOutputReceiver(), 10, TimeUnit.SECONDS)
        } finally {
            tempFile.delete()
        }
    }

    private var daemonThread: Thread? = null
    private var daemonStarted = false

    /**
     * Start the transport daemon on the device.
     * The daemon runs in blocking mode in a separate thread.
     */
    suspend fun startDaemon(): Boolean = withContext(Dispatchers.IO) {
        try {
            println("Starting transport daemon...")

            val receiver = SimpleOutputReceiver()

            // Check if daemon is already running by checking for the socket
            val preCheckReceiver = SimpleOutputReceiver()
            device.executeShellCommand("cat /proc/net/unix | grep 'AndroidStudioTransport$' 2>/dev/null", preCheckReceiver, 5, TimeUnit.SECONDS)
            // Socket must end with exactly "AndroidStudioTransport" (not "Agent")
            if (preCheckReceiver.output.lines().any { it.contains("AndroidStudioTransport") && !it.contains("Agent") }) {
                println("Transport daemon already running")
                return@withContext true
            }

            // Kill any existing daemon that might be stuck
            device.executeShellCommand("pkill -f '$DEVICE_DIR/transport' 2>/dev/null || true", receiver, 5, TimeUnit.SECONDS)
            delay(500)

            // Start daemon using shell script that stays running
            // Create a shell script that runs the daemon
            val scriptContent = """#!/system/bin/sh
$DEVICE_DIR/transport -config_file=$DEVICE_DIR/$DAEMON_CONFIG_FILE &
echo $${"$"}!
"""
            val scriptPath = "$DEVICE_DIR/start_daemon.sh"
            device.executeShellCommand("echo '$scriptContent' > $scriptPath && chmod 755 $scriptPath", receiver, 5, TimeUnit.SECONDS)

            // Run the script
            val startReceiver = SimpleOutputReceiver()
            device.executeShellCommand("$scriptPath", startReceiver, 5, TimeUnit.SECONDS)
            println("  Daemon start output: ${startReceiver.output.trim()}")

            // Give daemon time to start
            delay(2000)

            // Wait for daemon to start (check for socket) - check up to 10 seconds
            val maxWaitMs = 10_000L
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                delay(500)
                val checkReceiver = SimpleOutputReceiver()
                device.executeShellCommand("cat /proc/net/unix", checkReceiver, 5, TimeUnit.SECONDS)
                // Socket must end with exactly "AndroidStudioTransport" (not "Agent")
                val lines = checkReceiver.output.lines()
                val hasDaemonSocket = lines.any {
                    it.contains("@AndroidStudioTransport") && !it.contains("Agent")
                }
                if (hasDaemonSocket) {
                    val pidReceiver = SimpleOutputReceiver()
                    device.executeShellCommand("pgrep -f transport", pidReceiver, 5, TimeUnit.SECONDS)
                    val pid = pidReceiver.output.trim().split("\n").firstOrNull()?.trim() ?: "unknown"
                    println("Transport daemon started (PID: $pid)")
                    return@withContext true
                }
            }

            println("Warning: Could not verify daemon started after ${maxWaitMs}ms")
            println("  Try running the daemon manually: adb shell $DEVICE_DIR/transport -config_file=$DEVICE_DIR/$DAEMON_CONFIG_FILE &")
            return@withContext false
        } catch (e: Exception) {
            println("Failed to start daemon: ${e.message}")
            false
        }
    }

    /**
     * Connect to the transport daemon via gRPC.
     */
    suspend fun connect(port: Int = DEFAULT_LOCAL_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            localPort = port
            println("Connecting to transport daemon on port $localPort...")

            // Create port forward to the transport socket
            device.createForward(localPort, DEVICE_SOCKET_NAME, IDevice.DeviceUnixSocketNamespace.ABSTRACT)

            // Create gRPC channel with interceptor to log all calls
            channel = ManagedChannelBuilder.forAddress("localhost", localPort)
                .usePlaintext()
                .maxInboundMessageSize(512 * 1024 * 1024)
                .intercept(object : io.grpc.ClientInterceptor {
                    override fun <ReqT, RespT> interceptCall(
                        method: io.grpc.MethodDescriptor<ReqT, RespT>,
                        callOptions: io.grpc.CallOptions,
                        next: io.grpc.Channel
                    ): io.grpc.ClientCall<ReqT, RespT> {
                        println("  [gRPC] Calling: ${method.fullMethodName} (type: ${method.type})")
                        return object : io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                            next.newCall(method, callOptions)
                        ) {
                            override fun start(responseListener: io.grpc.ClientCall.Listener<RespT>, headers: io.grpc.Metadata) {
                                super.start(object : io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                                    override fun onMessage(message: RespT) {
                                        println("  [gRPC] Received message on ${method.fullMethodName}")
                                        super.onMessage(message)
                                    }
                                    override fun onClose(status: io.grpc.Status, trailers: io.grpc.Metadata) {
                                        println("  [gRPC] Call closed: ${method.fullMethodName} status=${status}")
                                        super.onClose(status, trailers)
                                    }
                                }, headers)
                            }
                        }
                    }
                })
                .build()

            stub = TransportServiceGrpc.newBlockingStub(channel)
            asyncStub = TransportServiceGrpc.newStub(channel)

            // Test connection
            val versionResponse = stub!!.getVersion(VersionRequest.getDefaultInstance())
            println("Connected to transport daemon v${versionResponse.version}")

            // Generate stream ID using SHA-256 of bootId + serial (like Android Studio does)
            streamId = calculateDeviceId()
            println("Stream ID: $streamId")

            // Start host-side proxy to receive events from device daemon
            proxy = TransportProxy(stub!!, asyncStub!!, streamId)
            proxy!!.start()
            println("Event proxy started")

            // Give the event stream time to fully establish
            // Increase delay to ensure gRPC streaming is stable
            delay(3000)
            println("Event stream ready")

            true
        } catch (e: Exception) {
            println("Connection failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Send an AppInspectionCommand and wait for the response.
     * Uses the host-side proxy to receive events from the device daemon.
     * @param timeoutMs Timeout in milliseconds (default 15 seconds)
     */
    private suspend fun sendAppInspectionCommand(
        pid: Int,
        appInspectionCommand: AppInspectionCommand,
        timeoutMs: Long = 15_000
    ): AppInspectionResponse {
        val p = proxy ?: throw TransportException("Proxy not initialized")
        return p.sendAppInspectionCommand(pid, sessionId, appInspectionCommand, timeoutMs)
    }

    /**
     * Get list of debuggable processes on the device using ddmlib.
     * The device daemon doesn't provide GetProcesses - it's handled by the host proxy.
     */
    fun getProcesses(): List<Process> {
        // Use ddmlib's Client objects which represent debuggable processes
        val clients = device.clients
        println("  Found ${clients.size} JDWP clients")

        return clients.mapNotNull { client ->
            val clientData = client.clientData
            val packageName = clientData.packageName ?: clientData.clientDescription ?: return@mapNotNull null
            val pid = clientData.pid
            println("    Client: $packageName (PID: $pid)")

            Process.newBuilder()
                .setDeviceId(streamId)
                .setPid(pid)
                .setName(packageName)
                .setState(Process.State.ALIVE)
                .setExposureLevel(Process.ExposureLevel.DEBUGGABLE)
                .build()
        }
    }

    /**
     * Find a process by package name.
     */
    fun findProcess(packageName: String): Process? {
        return getProcesses().find { it.name == packageName || it.name.contains(packageName) }
    }

    /**
     * Check if an agent is already attached to a process.
     */
    private fun isAgentAttached(pid: Int): Boolean {
        val checkReceiver = SimpleOutputReceiver()
        device.executeShellCommand(
            "cat /proc/net/unix | grep 'AndroidStudioTransportAgent$pid'",
            checkReceiver, 5, TimeUnit.SECONDS
        )
        return checkReceiver.output.contains("AndroidStudioTransportAgent$pid")
    }

    /**
     * Force restart an app to clear the old agent.
     */
    suspend fun forceRestartApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            println("Force restarting $packageName to clear old agent...")
            val receiver = SimpleOutputReceiver()

            // Stop the app
            device.executeShellCommand("am force-stop $packageName", receiver, 10, TimeUnit.SECONDS)
            delay(1000)

            // Launch the app again
            device.executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1", receiver, 10, TimeUnit.SECONDS)
            delay(2000)

            println("App restarted")
            true
        } catch (e: Exception) {
            println("Failed to restart app: ${e.message}")
            false
        }
    }

    /**
     * Attach the JVMTI agent to a process using the ATTACH_AGENT command through the transport daemon.
     * This is the same approach Android Studio uses.
     */
    suspend fun attachAgent(process: Process, forceReattach: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // Check if agent is already attached
        if (isAgentAttached(process.pid) && !forceReattach) {
            println("Agent already attached to process ${process.name} (PID: ${process.pid})")
            return@withContext true
        }

        try {
            println("Attaching agent to process ${process.name} (PID: ${process.pid}) via transport daemon...")

            val cpuArch = getCpuArch()
            val agentFileName = "libjvmtiagent_$cpuArch.so"

            // Build ATTACH_AGENT command - same as Android Studio does
            val commandId = nextCommandId()
            val attachCommand = Command.newBuilder()
                .setStreamId(streamId)
                .setPid(process.pid)
                .setType(Command.CommandType.ATTACH_AGENT)
                .setCommandId(commandId)
                .setAttachAgent(
                    AttachAgent.newBuilder()
                        .setAgentLibFileName(agentFileName)
                        .setAgentConfigPath("$DEVICE_DIR/$AGENT_CONFIG_FILE")
                        .setPackageName(process.name)
                        .build()
                )
                .build()

            println("  Sending ATTACH_AGENT command (commandId=$commandId)...")
            val response = stub?.execute(ExecuteRequest.newBuilder().setCommand(attachCommand).build())
            println("  ATTACH_AGENT response received")

            // Wait for agent to initialize
            println("  Waiting for agent to initialize...")
            delay(3000)

            // Verify agent connected by checking for its socket
            val socketReceiver = SimpleOutputReceiver()
            device.executeShellCommand("cat /proc/net/unix | grep AndroidStudioTransportAgent", socketReceiver, 5, TimeUnit.SECONDS)
            val socketOutput = socketReceiver.output.trim()
            if (socketOutput.contains("AndroidStudioTransportAgent${process.pid}")) {
                println("  Agent socket found: $socketOutput")
                println("Agent attached successfully")
                true
            } else {
                println("  Warning: Agent socket not found, trying fallback method...")
                // Fallback to direct am attach-agent
                attachAgentDirectly(process)
            }
        } catch (e: Exception) {
            println("Failed to attach agent via transport: ${e.message}")
            println("Trying fallback method...")
            attachAgentDirectly(process)
        }
    }

    /**
     * Fallback method: Attach agent directly using am attach-agent.
     */
    private suspend fun attachAgentDirectly(process: Process): Boolean = withContext(Dispatchers.IO) {
        try {
            val cpuArch = getCpuArch()
            val agentFileName = "libjvmtiagent_$cpuArch.so"
            val sourceAgentPath = "$DEVICE_DIR/$agentFileName"
            val packageName = process.name

            // Due to SELinux, apps can't load libraries from /data/local/tmp
            // We need to copy the agent to the app's code_cache directory
            val appCacheDir = "/data/data/$packageName/code_cache"
            val appAgentPath = "$appCacheDir/$agentFileName"
            val appConfigPath = "$appCacheDir/$AGENT_CONFIG_FILE"
            val appPerfaPath = "$appCacheDir/perfa.jar"

            println("  Copying agent files to app's code_cache directory...")

            // Create code_cache dir and copy files using run-as
            val receiver = SimpleOutputReceiver()
            val copyCmd = """
                run-as $packageName mkdir -p $appCacheDir 2>/dev/null;
                run-as $packageName cp $sourceAgentPath $appAgentPath 2>/dev/null || cat $sourceAgentPath | run-as $packageName sh -c 'cat > $appAgentPath';
                run-as $packageName chmod 755 $appAgentPath;
                cat $DEVICE_DIR/$AGENT_CONFIG_FILE | run-as $packageName sh -c 'cat > $appConfigPath';
                cat $DEVICE_DIR/perfa.jar | run-as $packageName sh -c 'cat > $appPerfaPath' 2>/dev/null || true;
            """.trimIndent().replace("\n", " ")

            device.executeShellCommand(copyCmd, receiver, 15, TimeUnit.SECONDS)

            // Use am attach-agent with the app's private copy
            val attachCmd = "am attach-agent $packageName $appAgentPath=$appConfigPath"
            println("  Executing: $attachCmd")

            val attachReceiver = SimpleOutputReceiver()
            device.executeShellCommand(attachCmd, attachReceiver, 15, TimeUnit.SECONDS)

            val output = attachReceiver.output.trim()
            if (output.isNotEmpty() && output.contains("error", ignoreCase = true)) {
                println("Failed to attach agent: $output")
                return@withContext false
            }

            // Wait for agent to initialize
            delay(3000)

            // Verify agent connected
            val socketReceiver = SimpleOutputReceiver()
            device.executeShellCommand("cat /proc/net/unix | grep AndroidStudioTransportAgent", socketReceiver, 5, TimeUnit.SECONDS)
            val socketOutput = socketReceiver.output.trim()
            if (socketOutput.contains("AndroidStudioTransportAgent")) {
                println("  Agent socket found via fallback method")
                println("Agent attached successfully (fallback)")
                true
            } else {
                println("  Agent socket not found")
                false
            }
        } catch (e: Exception) {
            println("Fallback attach failed: ${e.message}")
            false
        }
    }

    /**
     * Test direct connection to the agent socket.
     * The agent has its own gRPC server that might accept commands directly.
     */
    suspend fun testAgentSocket(process: Process): Boolean = withContext(Dispatchers.IO) {
        try {
            val agentSocketName = "AndroidStudioTransportAgent${process.pid}"
            println("Testing direct agent socket connection: $agentSocketName")

            // Forward agent socket
            val agentPort = 12390
            device.createForward(agentPort, agentSocketName, IDevice.DeviceUnixSocketNamespace.ABSTRACT)

            // Create gRPC channel
            val agentChannel = ManagedChannelBuilder.forAddress("localhost", agentPort)
                .usePlaintext()
                .maxInboundMessageSize(512 * 1024 * 1024)
                .build()

            try {
                // Try to call getVersion on agent
                val agentStub = TransportServiceGrpc.newBlockingStub(agentChannel)
                println("  Calling getVersion on agent socket...")
                val versionResponse = agentStub.getVersion(VersionRequest.getDefaultInstance())
                println("  Agent version: ${versionResponse.version}")

                // Try getEvents on agent
                println("  Starting getEvents stream on agent...")
                val eventsRequest = GetEventsRequest.newBuilder().build()
                val asyncAgentStub = TransportServiceGrpc.newStub(agentChannel)

                var agentEventCount = 0
                asyncAgentStub.getEvents(eventsRequest, object : io.grpc.stub.StreamObserver<Event> {
                    override fun onNext(event: Event) {
                        agentEventCount++
                        println("  [Agent] Event #$agentEventCount: kind=${event.kind} cmdId=${event.commandId}")
                    }
                    override fun onError(t: Throwable) {
                        println("  [Agent] Stream error: ${t.message}")
                    }
                    override fun onCompleted() {
                        println("  [Agent] Stream completed")
                    }
                })

                // Give time for events
                delay(2000)
                println("  Agent events received: $agentEventCount")

                true
            } catch (e: Exception) {
                println("  Agent socket error: ${e.javaClass.simpleName}: ${e.message}")
                false
            } finally {
                agentChannel.shutdown()
                device.removeForward(agentPort)
            }
        } catch (e: Exception) {
            println("  Agent socket test failed: ${e.message}")
            false
        }
    }

    /**
     * Create agent configuration for a specific package.
     */
    private fun createAgentConfig(packageName: String): AgentConfig {
        val socketType = if (apiLevel >= 26) {
            CommonConfig.SocketType.ABSTRACT_SOCKET
        } else {
            CommonConfig.SocketType.UNSPECIFIED_SOCKET
        }

        val commonConfig = CommonConfig.newBuilder()
            .setSocketType(socketType)
            .setServiceAddress("127.0.0.1:$DEVICE_PORT")
            .setServiceSocketName(DEVICE_SOCKET_NAME)
            .build()

        val memConfig = AgentConfig.MemoryConfig.newBuilder()
            .setMaxStackDepth(50)
            .setTrackGlobalJniRefs(false)
            .setAppDir("/data/data/$packageName")
            .build()

        return AgentConfig.newBuilder()
            .setCommon(commonConfig)
            .setMem(memConfig)
            .setCpuApiTracingEnabled(false)
            .setAttachMethod(AgentConfig.AttachAgentMethod.INSTANT)
            .build()
    }

    /**
     * Test the daemon by sending an ECHO command.
     * Tests multiple methods of receiving events.
     */
    suspend fun testEcho(): Boolean = withContext(Dispatchers.IO) {
        try {
            println("Testing ECHO command...")
            val commandId = nextCommandId()

            // Send ECHO command
            println("  Sending ECHO command (commandId=$commandId)...")
            val command = Command.newBuilder()
                .setStreamId(streamId)
                .setType(Command.CommandType.ECHO)
                .setCommandId(commandId)
                .build()
            stub?.execute(ExecuteRequest.newBuilder().setCommand(command).build())
            println("  ECHO command sent")

            // Wait for daemon to process
            delay(1000)

            // Method 1: Try polling with getEvents (non-blocking iterator check)
            println("  Testing getEvents poll...")
            try {
                val eventsRequest = GetEventsRequest.newBuilder().build()
                val eventIterator = stub!!.getEvents(eventsRequest)

                // Check if there are any events in the first 2 seconds
                var polledEvents = 0
                val pollEnd = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < pollEnd) {
                    // This will block if no events, but we're in a coroutine
                    delay(100)
                    // The iterator blocks on hasNext, so we can't really poll this way
                }
                println("  getEvents poll timeout (iterator blocks on hasNext)")
            } catch (e: Exception) {
                println("  getEvents poll error: ${e.message}")
            }

            // Method 2: Try getEventGroups for ECHO events
            println("  Testing getEventGroups for ECHO...")
            try {
                val groupRequest = GetEventGroupsRequest.newBuilder()
                    .setStreamId(streamId)
                    .setKind(Event.Kind.ECHO)
                    .build()
                val groupResponse = stub!!.getEventGroups(groupRequest)
                val echoGroups = groupResponse.groupsList
                println("  getEventGroups returned ${echoGroups.size} groups")
                for (group in echoGroups) {
                    println("    Group ${group.groupId}: ${group.eventsCount} events")
                    for (event in group.eventsList) {
                        println("      Event: kind=${event.kind} cmdId=${event.commandId}")
                    }
                }
            } catch (e: Exception) {
                println("  getEventGroups error: ${e.message}")
            }

            // Method 3: Test with streamId = 0 (all streams)
            println("  Testing getEventGroups with streamId=0...")
            try {
                val groupRequest = GetEventGroupsRequest.newBuilder()
                    .setStreamId(0)
                    .setKind(Event.Kind.ECHO)
                    .build()
                val groupResponse = stub!!.getEventGroups(groupRequest)
                println("  getEventGroups(streamId=0) returned ${groupResponse.groupsCount} groups")
            } catch (e: Exception) {
                println("  getEventGroups(streamId=0) error: ${e.message}")
            }

            println("ECHO test done")
            true
        } catch (e: Exception) {
            println("ECHO test failed: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Begin a profiling session for a process.
     * This must be called before launching inspectors.
     */
    suspend fun beginSession(process: Process): Boolean = withContext(Dispatchers.IO) {
        try {
            println("Beginning session for process ${process.name} (PID: ${process.pid})...")

            // Generate a unique session ID
            sessionId = System.currentTimeMillis()

            val commandId = nextCommandId()

            val beginSessionCmd = BeginSession.newBuilder()
                .setSessionId(sessionId)
                .setPid(process.pid)
                .setProcessName(process.name)
                .setRequestProcessStart(false)
                .setJvmtiAgentEnabled(true)
                .setLiveAllocEnabled(false)
                .build()

            val command = Command.newBuilder()
                .setStreamId(streamId)
                .setType(Command.CommandType.BEGIN_SESSION)
                .setPid(process.pid)
                .setCommandId(commandId)
                .setSessionId(sessionId)
                .setBeginSession(beginSessionCmd)
                .build()

            stub?.execute(ExecuteRequest.newBuilder().setCommand(command).build())

            // Give the daemon time to process
            delay(500)

            println("Session started (sessionId=$sessionId)")
            true
        } catch (e: Exception) {
            println("Failed to begin session: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Launch an inspector using gRPC event-based communication.
     */
    suspend fun launchInspector(
        process: Process,
        inspectorId: String,
        dexPath: String = "$DEVICE_DIR/layoutinspector-view-inspection.jar"
    ): InspectorConnection? = withContext(Dispatchers.IO) {
        try {
            println("Launching inspector: $inspectorId...")

            // Build CreateInspector command
            val commandId = nextCommandId()
            val appInspectionCmd = AppInspectionCommand.newBuilder()
                .setInspectorId(inspectorId)
                .setCommandId(commandId)
                .setCreateInspectorCommand(
                    CreateInspectorCommand.newBuilder()
                        .setDexPath(dexPath)
                        .setLaunchMetadata(
                            LaunchMetadata.newBuilder()
                                .setLaunchedByName("layout-inspector-cli")
                                .setForce(true)
                                .build()
                        )
                        .build()
                )
                .build()

            println("  Sending CreateInspector command (commandId=$commandId)...")
            println("  Waiting for response using event streaming...")

            // Use longer timeout for CreateInspector (30 seconds) as it needs to load DEX
            val response = sendAppInspectionCommand(process.pid, appInspectionCmd, timeoutMs = 30_000)

            println("  Response: status=${response.status} commandId=${response.commandId}")

            if (response.hasCreateInspectorResponse()) {
                val createResponse = response.createInspectorResponse
                if (createResponse.status == CreateInspectorResponse.Status.SUCCESS) {
                    println("Inspector launched successfully!")
                    return@withContext InspectorConnection(
                        this@TransportService,
                        process.pid,
                        inspectorId
                    )
                } else {
                    println("Inspector launch failed: ${createResponse.status} - ${response.errorMessage}")
                }
            } else {
                println("Unexpected response: $response")
            }

            null
        } catch (e: Exception) {
            println("Failed to launch inspector: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Send a raw command to an inspector and get the response.
     * Uses gRPC event-based communication.
     */
    suspend fun sendRawInspectorCommand(
        pid: Int,
        inspectorId: String,
        commandBytes: ByteArray
    ): ByteArray {
        val commandId = nextCommandId()
        val appInspectionCmd = AppInspectionCommand.newBuilder()
            .setInspectorId(inspectorId)
            .setCommandId(commandId)
            .setRawInspectorCommand(
                RawCommand.newBuilder()
                    .setContent(com.google.protobuf.ByteString.copyFrom(commandBytes))
                    .build()
            )
            .build()

        val response = sendAppInspectionCommand(pid, appInspectionCmd)

        if (response.hasRawResponse()) {
            return response.rawResponse.content.toByteArray()
        }

        throw TransportException("Expected RawResponse but got: $response")
    }

    /**
     * Get all collected layout events from the proxy.
     */
    fun getLayoutEvents(): List<com.android.tools.idea.layoutinspector.view.inspection.LayoutEvent> {
        return proxy?.getLayoutEvents() ?: emptyList()
    }

    /**
     * Get all collected window roots events from the proxy.
     */
    fun getWindowRootsEvents(): List<com.android.tools.idea.layoutinspector.view.inspection.WindowRootsEvent> {
        return proxy?.getWindowRootsEvents() ?: emptyList()
    }

    /**
     * Get all raw inspector events for debugging.
     */
    fun getRawInspectorEvents(): List<ByteArray> {
        return proxy?.getRawInspectorEvents() ?: emptyList()
    }

    /**
     * Clear all collected events.
     */
    fun clearEvents() {
        proxy?.clearEvents()
    }

    /**
     * Get raw layout event bytes (when proto parsing fails).
     */
    fun getRawLayoutEventBytes(): ByteArray? {
        return proxy?.getRawLayoutEventBytes()
    }

    override fun close() {
        // Stop the host-side proxy
        proxy?.stop()
        proxy = null

        try {
            channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            device.removeForward(localPort)
        } catch (e: Exception) {
            // Ignore
        }
    }
}

/**
 * Connection to a running inspector.
 * Uses gRPC event-based communication through the TransportService.
 */
class InspectorConnection(
    private val transportService: TransportService,
    private val pid: Int,
    val inspectorId: String
) {
    /**
     * Send a raw command to the inspector and get the response.
     */
    suspend fun sendCommand(commandBytes: ByteArray): ByteArray {
        return transportService.sendRawInspectorCommand(pid, inspectorId, commandBytes)
    }

    /**
     * Get all collected layout events.
     */
    fun getLayoutEvents(): List<com.android.tools.idea.layoutinspector.view.inspection.LayoutEvent> {
        return transportService.getLayoutEvents()
    }

    /**
     * Get all collected window roots events.
     */
    fun getWindowRootsEvents(): List<com.android.tools.idea.layoutinspector.view.inspection.WindowRootsEvent> {
        return transportService.getWindowRootsEvents()
    }

    /**
     * Clear all collected events.
     */
    fun clearEvents() {
        transportService.clearEvents()
    }

    /**
     * Get raw layout event bytes (when proto parsing fails).
     */
    fun getRawLayoutEventBytes(): ByteArray? {
        return transportService.getRawLayoutEventBytes()
    }
}

/**
 * Simple output receiver for shell commands.
 */
class SimpleOutputReceiver : com.android.ddmlib.MultiLineReceiver() {
    private val builder = StringBuilder()
    val output: String get() = builder.toString()

    override fun processNewLines(lines: Array<out String>) {
        lines.forEach { builder.appendLine(it) }
    }

    override fun isCancelled(): Boolean = false
}
