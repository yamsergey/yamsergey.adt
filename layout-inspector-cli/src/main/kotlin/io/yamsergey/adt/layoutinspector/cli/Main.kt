package io.yamsergey.adt.layoutinspector.cli

import com.android.tools.idea.layoutinspector.view.inspection.Command
import com.android.tools.idea.layoutinspector.view.inspection.Response
import com.android.tools.idea.layoutinspector.view.inspection.StartFetchCommand
import com.android.tools.idea.layoutinspector.view.inspection.CaptureSnapshotCommand
import com.android.tools.idea.layoutinspector.view.inspection.Screenshot
import io.yamsergey.adt.layoutinspector.transport.AdbHelper
import io.yamsergey.adt.layoutinspector.transport.TransportService
import io.yamsergey.adt.layoutinspector.snapshot.SnapshotMetadata
import io.yamsergey.adt.layoutinspector.snapshot.SnapshotSaver
import layoutinspector.snapshots.Metadata
import layoutinspector.snapshots.Snapshot
import picocli.CommandLine
import picocli.CommandLine.Command as CliCommand
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@CliCommand(
    name = "layout-inspector-cli",
    mixinStandardHelpOptions = true,
    version = ["1.0.0"],
    description = ["Capture Layout Inspector snapshots from Android devices"]
)
class LayoutInspectorCli : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["Target application package name"],
        arity = "0..1"
    )
    var packageName: String? = null

    @Option(
        names = ["-s", "--serial"],
        description = ["Device serial number (auto-detect if only one device)"]
    )
    var deviceSerial: String? = null

    @Option(
        names = ["-o", "--output"],
        description = ["Output file (default: snapshot.json for JSON, snapshot.li for binary)"]
    )
    var outputFile: File? = null

    @Option(
        names = ["--json"],
        description = ["Output as JSON (human-readable layout tree). This is the default."],
        negatable = true,
        defaultValue = "true",
        fallbackValue = "true"
    )
    var jsonOutput: Boolean = true  // Default to JSON now

    @Option(
        names = ["--binary", "--li"],
        description = ["Output as binary .li format (Android Studio compatible attempt)"]
    )
    var binaryOutput: Boolean = false

    @Option(
        names = ["--no-compose"],
        description = ["Skip Compose data collection"]
    )
    var skipCompose: Boolean = false

    @Option(
        names = ["--compose-version"],
        description = ["Compose UI version to use for inspector (e.g., 1.7.0). Required for Compose hierarchy capture."]
    )
    var composeVersion: String? = null

    @Option(
        names = ["--timeout"],
        description = ["Connection timeout in milliseconds (default: 30000)"]
    )
    var timeoutMs: Long = 30_000

    @Option(
        names = ["-v", "--verbose"],
        description = ["Enable verbose output"]
    )
    var verbose: Boolean = false

    @Option(
        names = ["--list-devices"],
        description = ["List connected devices and exit"]
    )
    var listDevices: Boolean = false

    @Option(
        names = ["--list-processes"],
        description = ["List debuggable processes on the device"]
    )
    var listProcesses: Boolean = false

    @Option(
        names = ["--android-studio"],
        description = ["Path to Android Studio installation (default: /snap/android-studio/current)"]
    )
    var androidStudioPath: String = "/snap/android-studio/current"

    @Option(
        names = ["--deploy-only"],
        description = ["Only deploy transport daemon to device, don't capture"]
    )
    var deployOnly: Boolean = false

    @Option(
        names = ["--convert"],
        description = ["Convert an existing .li snapshot file to JSON (no device needed)"]
    )
    var convertFile: File? = null

    override fun call(): Int {
        // Convert mode - parse existing .li file and output JSON
        if (convertFile != null) {
            return convertLiToJson(convertFile!!)
        }

        val adbHelper = AdbHelper(timeoutMs = timeoutMs)

        try {
            if (verbose) println("Initializing ADB...")
            adbHelper.initialize()

            if (verbose) println("Waiting for ADB connection...")
            kotlinx.coroutines.runBlocking {
                adbHelper.waitForConnection()
            }

            // List devices mode
            if (listDevices) {
                return listConnectedDevices(adbHelper)
            }

            // Get target device
            val device = adbHelper.getTargetDevice(deviceSerial)
            if (device == null) {
                System.err.println("Error: No device found" +
                    (deviceSerial?.let { " with serial '$it'" } ?: ""))
                return 1
            }

            if (verbose) {
                println("Connected to device: ${device.serialNumber}")
                println("  Model: ${device.getProperty("ro.product.model")}")
                println("  API Level: ${adbHelper.getApiLevel(device)}")
                println("  ABI: ${adbHelper.getAbi(device)}")
            }

            // Wait for JDWP clients to be discovered if not already
            if (device.clients.isEmpty()) {
                if (verbose) println("Waiting for debuggable processes to be discovered...")
                kotlinx.coroutines.runBlocking {
                    adbHelper.waitForClients(device)
                }
            }

            // List processes mode
            if (listProcesses) {
                return listDeviceProcesses(adbHelper, device)
            }

            // Create transport service
            val transportService = TransportService(adbHelper, device, androidStudioPath)

            // Deploy to device
            kotlinx.coroutines.runBlocking {
                if (!transportService.deployToDevice()) {
                    System.err.println("Error: Failed to deploy transport daemon")
                    return@runBlocking 1
                }

                if (deployOnly) {
                    println("Deployment complete. Use without --deploy-only to capture snapshots.")
                    return@runBlocking 0
                }

                // Start daemon
                if (!transportService.startDaemon()) {
                    System.err.println("Error: Failed to start transport daemon")
                    return@runBlocking 1
                }

                // Connect to daemon
                if (!transportService.connect()) {
                    System.err.println("Error: Failed to connect to transport daemon")
                    return@runBlocking 1
                }

                // Test ECHO command
                transportService.testEcho()

                // Capture mode - requires package name
                if (packageName == null) {
                    // List available processes
                    println("\nAvailable debuggable processes:")
                    transportService.getProcesses().forEach { proc ->
                        println("  ${proc.pid}\t${proc.name}")
                    }
                    println("\nUsage: layout-inspector-cli <package-name> [-o output.li]")
                    return@runBlocking 0
                }

                // Find target process
                val process = transportService.findProcess(packageName!!)
                if (process == null) {
                    System.err.println("Error: Process '$packageName' not found")
                    System.err.println("Available processes:")
                    transportService.getProcesses().forEach { proc ->
                        println("  ${proc.pid}\t${proc.name}")
                    }
                    return@runBlocking 1
                }

                println("Found process: ${process.name} (PID: ${process.pid})")

                // Attach agent
                if (!transportService.attachAgent(process)) {
                    System.err.println("Error: Failed to attach agent to process")
                    return@runBlocking 1
                }

                // Test direct agent socket (experimental)
                transportService.testAgentSocket(process)

                // Begin session
                if (!transportService.beginSession(process)) {
                    System.err.println("Error: Failed to begin session")
                    return@runBlocking 1
                }

                // Launch view inspector
                val viewInspector = transportService.launchInspector(
                    process,
                    "layoutinspector.view.inspection"
                )
                if (viewInspector == null) {
                    System.err.println("Error: Failed to launch view inspector")
                    return@runBlocking 1
                }

                // Launch compose inspector (optional - requires Compose version to be specified)
                var composeInspector: io.yamsergey.adt.layoutinspector.transport.InspectorConnection? = null
                if (!skipCompose && composeVersion != null) {
                    println("Deploying Compose inspector for version $composeVersion...")
                    val composeInspectorPath = transportService.deployComposeInspector(composeVersion!!)
                    if (composeInspectorPath != null) {
                        println("Launching Compose inspector...")
                        composeInspector = transportService.launchInspector(
                            process,
                            "layoutinspector.compose.inspection",
                            composeInspectorPath
                        )
                        if (composeInspector == null) {
                            println("  Note: Compose inspector failed to launch")
                        } else {
                            println("  Compose inspector launched successfully")
                        }
                    } else {
                        println("  Note: Failed to deploy Compose inspector")
                    }
                } else if (!skipCompose) {
                    println("Skipping Compose inspector (use --compose-version to enable)")
                }

                // Capture snapshot
                println("Capturing snapshot...")
                val captureResult = captureSnapshot(viewInspector, composeInspector)
                if (captureResult == null) {
                    System.err.println("Error: Failed to capture snapshot")
                    return@runBlocking 1
                }

                // Determine output format and file
                val useJson = jsonOutput && !binaryOutput
                val actualOutputFile = outputFile ?: File(if (useJson) "snapshot.json" else "snapshot.li")

                // Save snapshot
                println("Saving snapshot to: ${actualOutputFile.absolutePath}")
                if (useJson) {
                    saveSnapshotAsJson(
                        actualOutputFile.toPath(),
                        captureResult,
                        adbHelper.getApiLevel(device),
                        process.name
                    )
                } else {
                    saveSnapshotFromEvents(
                        actualOutputFile.toPath(),
                        captureResult,
                        adbHelper.getApiLevel(device),
                        process.name
                    )
                }

                println("Snapshot saved successfully!")
                transportService.close()
                0
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            return 1
        } finally {
            adbHelper.terminate()
        }

        return 0
    }

    private suspend fun captureSnapshot(
        viewInspector: io.yamsergey.adt.layoutinspector.transport.InspectorConnection,
        composeInspector: io.yamsergey.adt.layoutinspector.transport.InspectorConnection?
    ): LayoutCaptureResult? {
        try {
            // Clear any previous events
            viewInspector.clearEvents()

            // First, start fetching (non-continuous mode triggers a single capture)
            val startCmd = Command.newBuilder()
                .setStartFetchCommand(
                    StartFetchCommand.newBuilder()
                        .setContinuous(false)
                        .build()
                )
                .build()

            // Try to send StartFetch - but don't fail if response times out
            // The layout events come separately as APP_INSPECTION_EVENTs
            var startFetchSent = false
            try {
                val startResponseBytes = viewInspector.sendCommand(startCmd.toByteArray())
                println("  StartFetch: Received ${startResponseBytes.size} bytes")
                val startResponse = Response.parseFrom(startResponseBytes)
                println("  StartFetch response case: hasStartFetch=${startResponse.hasStartFetchResponse()}")
                if (startResponse.hasStartFetchResponse()) {
                    if (startResponse.startFetchResponse.error.isNotEmpty()) {
                        println("Warning: Start fetch returned error: ${startResponse.startFetchResponse.error}")
                        return null
                    } else {
                        println("  StartFetch succeeded")
                    }
                }
                startFetchSent = true
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                println("  StartFetch response timed out, but layout events may still be arriving...")
                startFetchSent = true  // Command was sent, just response timed out
            }

            // Wait for layout events to arrive (may take several seconds for large layouts)
            println("  Waiting for layout events...")
            // Wait longer and check periodically for events
            for (i in 1..10) {
                kotlinx.coroutines.delay(1000)
                val rawBytes = viewInspector.getRawLayoutEventBytes()
                if (rawBytes != null && rawBytes.size > 100000) {
                    println("  Received large layout event after ${i}s")
                    break
                }
            }

            // Check what events we received
            val layoutEvents = viewInspector.getLayoutEvents()
            val rootsEvents = viewInspector.getWindowRootsEvents()
            val rawLayoutBytes = viewInspector.getRawLayoutEventBytes()

            println("  Collected ${layoutEvents.size} layout events and ${rootsEvents.size} roots events")
            if (rawLayoutBytes != null) {
                println("  Raw layout event bytes: ${rawLayoutBytes.size} bytes (proto parsing failed)")
            }

            // Capture Compose data if inspector is available
            var composeData: ComposeData? = null
            if (composeInspector != null && rootsEvents.isNotEmpty()) {
                // Get the window root ID from the WindowRootsEvent
                val windowRootId = rootsEvents.last().let { roots ->
                    if (roots.idsCount > 0) roots.getIds(0) else null
                }
                if (windowRootId != null) {
                    println("  Capturing Compose hierarchy for window root $windowRootId...")
                    composeData = captureComposeData(composeInspector, windowRootId)
                } else {
                    println("  No window root ID available for Compose capture")
                }
            }

            // First try parsed LayoutEvents
            if (layoutEvents.isNotEmpty()) {
                // Use the first (or most recent) layout event
                val layoutEvent = layoutEvents.first()
                println("  Using LayoutEvent: hasRootView=${layoutEvent.hasRootView()} hasScreenshot=${layoutEvent.hasScreenshot()}")

                if (layoutEvent.hasRootView()) {
                    val rootView = layoutEvent.rootView
                    println("  RootView: childrenCount=${rootView.childrenCount}")
                    if (rootView.hasNode()) {
                        val node = rootView.node
                        println("  RootNode: id=${node.id} class=${node.className}")
                    }
                    printViewTree(rootView, "  ")
                }

                if (layoutEvent.hasScreenshot()) {
                    val screenshot = layoutEvent.screenshot
                    println("  Screenshot: type=${screenshot.type} size=${screenshot.bytes.size()} bytes")
                }

                // On API 36+, hasRootView() may return false even though view tree is in app_context
                // If we have raw bytes, include them so saveRawLayoutAsJson can parse the view tree
                return LayoutCaptureResult(
                    layoutEvent = layoutEvent,
                    windowRoots = rootsEvents.lastOrNull(),
                    rawLayoutEventBytes = if (!layoutEvent.hasRootView()) rawLayoutBytes else null,
                    composeData = composeData
                )
            }

            // If no parsed events, use raw bytes
            if (rawLayoutBytes != null) {
                println("  Using raw layout event bytes directly")
                return LayoutCaptureResult(
                    layoutEvent = null,
                    windowRoots = rootsEvents.lastOrNull(),
                    rawLayoutEventBytes = rawLayoutBytes,
                    composeData = composeData
                )
            }

            // If we have compose data but no layout, still return what we have
            if (composeData != null && (composeData.composablesResponse != null || composeData.parametersResponse != null)) {
                println("  No view layout events, but have Compose data - proceeding")
                return LayoutCaptureResult(
                    layoutEvent = null,
                    windowRoots = rootsEvents.lastOrNull(),
                    rawLayoutEventBytes = null,
                    composeData = composeData
                )
            }

            println("  No layout events received")
            return null
        } catch (e: Exception) {
            println("Error capturing snapshot: ${e.message}")
            if (verbose) e.printStackTrace()
            return null
        }
    }

    private suspend fun captureComposeData(
        composeInspector: io.yamsergey.adt.layoutinspector.transport.InspectorConnection,
        rootViewId: Long
    ): ComposeData? {
        try {
            // Send GetComposablesCommand
            val getComposablesCmd = layoutinspector.compose.inspection.Command.newBuilder()
                .setGetComposablesCommand(
                    layoutinspector.compose.inspection.GetComposablesCommand.newBuilder()
                        .setRootViewId(rootViewId)
                        .setGeneration(0)
                        .setExtractAllParameters(true)
                        .build()
                )
                .build()

            if (verbose) println("    Sending GetComposablesCommand for rootViewId=$rootViewId...")
            val composablesResponseBytes = composeInspector.sendCommand(getComposablesCmd.toByteArray())
            if (verbose) println("    GetComposables: Received ${composablesResponseBytes.size} bytes")

            var composablesResponse: layoutinspector.compose.inspection.GetComposablesResponse? = null
            var rawComposablesBytes: ByteArray? = null

            try {
                val response = layoutinspector.compose.inspection.Response.parseFrom(composablesResponseBytes)
                if (response.hasGetComposablesResponse()) {
                    composablesResponse = response.getComposablesResponse
                    val rootCount = composablesResponse.rootsCount
                    val stringCount = composablesResponse.stringsCount
                    println("    Composables: $rootCount roots, $stringCount strings")
                } else {
                    println("    No GetComposablesResponse in response")
                }
            } catch (e: Exception) {
                println("    Failed to parse GetComposablesResponse: ${e.message}")
                rawComposablesBytes = composablesResponseBytes
            }

            // Also get all parameters (optional - don't fail if this times out)
            var paramsResponse: layoutinspector.compose.inspection.GetAllParametersResponse? = null
            var rawParamsBytes: ByteArray? = null

            try {
                val getParamsCmd = layoutinspector.compose.inspection.Command.newBuilder()
                    .setGetAllParametersCommand(
                        layoutinspector.compose.inspection.GetAllParametersCommand.newBuilder()
                            .setRootViewId(rootViewId)
                            .setGeneration(0)
                            .build()
                    )
                    .build()

                println("    Sending GetAllParametersCommand...")
                val paramsResponseBytes = composeInspector.sendCommand(getParamsCmd.toByteArray())
                println("    GetAllParameters: Received ${paramsResponseBytes.size} bytes")

                // Debug: save parameters response to file
                if (verbose) {
                    java.io.File("/tmp/params-response.bin").writeBytes(paramsResponseBytes)
                    println("    Saved parameters response to /tmp/params-response.bin")
                }

                try {
                    val response = layoutinspector.compose.inspection.Response.parseFrom(paramsResponseBytes)
                    if (response.hasGetAllParametersResponse()) {
                        paramsResponse = response.getAllParametersResponse
                        val paramCount = paramsResponse.parameterGroupsCount
                        val stringCount = paramsResponse.stringsCount
                        println("    Parameters: $paramCount parameter groups, $stringCount strings")

                        // Debug: print string table
                        if (verbose && stringCount > 0) {
                            println("    Parameters string table:")
                            paramsResponse.stringsList.take(20).forEach { entry ->
                                println("      ${entry.id} -> '${entry.str}'")
                            }
                        }
                    } else {
                        println("    No GetAllParametersResponse in response")
                    }
                } catch (e: Exception) {
                    println("    Failed to parse GetAllParametersResponse: ${e.message}")
                    rawParamsBytes = paramsResponseBytes
                }
            } catch (e: Exception) {
                println("    Warning: Could not get parameters: ${e.message}")
                // Continue without parameters - we still have composables
            }

            return ComposeData(
                viewId = rootViewId,
                composablesResponse = composablesResponse,
                parametersResponse = paramsResponse,
                rawComposablesBytes = rawComposablesBytes,
                rawParametersBytes = rawParamsBytes
            )
        } catch (e: Exception) {
            println("    Error capturing Compose data: ${e.message}")
            if (verbose) e.printStackTrace()
            return null
        }
    }

    private fun printViewTree(node: com.android.tools.idea.layoutinspector.view.inspection.ViewNode, indent: String = "", maxDepth: Int = 3) {
        if (indent.length / 2 > maxDepth) {
            if (node.childrenCount > 0) {
                println("$indent... (${node.childrenCount} more children)")
            }
            return
        }

        val nodeData = if (node.hasNode()) node.node else null
        val className = nodeData?.className ?: "unknown"
        val id = nodeData?.id ?: 0
        val resourceName = if (nodeData?.hasResource() == true) nodeData.resource.name else ""
        val bounds = if (nodeData?.hasBounds() == true) {
            val b = nodeData.bounds
            "${b.x},${b.y} ${b.w}x${b.h}"
        } else ""

        val displayName = if (resourceName.isNotEmpty()) "$className(@$resourceName)" else className
        println("$indent- $displayName [$bounds] (${node.childrenCount} children)")

        for (child in node.childrenList) {
            printViewTree(child, "$indent  ", maxDepth)
        }
    }

    data class LayoutCaptureResult(
        val layoutEvent: com.android.tools.idea.layoutinspector.view.inspection.LayoutEvent?,
        val windowRoots: com.android.tools.idea.layoutinspector.view.inspection.WindowRootsEvent?,
        val rawLayoutEventBytes: ByteArray? = null,
        val composeData: ComposeData? = null
    )

    data class ComposeData(
        val viewId: Long,
        val composablesResponse: layoutinspector.compose.inspection.GetComposablesResponse?,
        val parametersResponse: layoutinspector.compose.inspection.GetAllParametersResponse?,
        val rawComposablesBytes: ByteArray? = null,
        val rawParametersBytes: ByteArray? = null
    )

    private fun saveSnapshotAsJson(
        path: Path,
        captureResult: LayoutCaptureResult,
        apiLevel: Int,
        processName: String
    ) {
        val layoutEvent = captureResult.layoutEvent
        val rawLayoutBytes = captureResult.rawLayoutEventBytes
        val composeData = captureResult.composeData

        // If we have raw bytes, use the same extraction logic as for .li files
        if (rawLayoutBytes != null && rawLayoutBytes.size > 1000) {
            saveRawLayoutAsJson(path, rawLayoutBytes, apiLevel, processName, composeData)
            return
        }

        // If we have compose data but no view layout, save compose-only JSON
        if (composeData?.composablesResponse != null && layoutEvent == null && rawLayoutBytes == null) {
            saveComposeOnlyAsJson(path, composeData, apiLevel, processName)
            return
        }

        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "metadata": {""")
        sb.appendLine("""    "processName": "$processName",""")
        sb.appendLine("""    "apiLevel": $apiLevel,""")
        sb.appendLine("""    "captureTime": "${java.time.Instant.now()}"""")
        sb.appendLine("  },")

        if (layoutEvent != null && layoutEvent.hasRootView()) {
            sb.appendLine("""  "viewTree":""")
            viewNodeToJson(layoutEvent.rootView, sb, "  ")
        } else {
            sb.appendLine("""  "viewTree": null""")
        }

        // Add Compose data if available
        if (composeData?.composablesResponse != null) {
            sb.appendLine(",")
            sb.appendLine("""  "composeTree":""")
            composeDataToJson(composeData, sb, "  ")
        }

        sb.appendLine("}")

        path.toFile().writeText(sb.toString())
        println("  Saved JSON layout tree")
    }

    /**
     * Save compose-only snapshot when no view layout data is available.
     */
    private fun saveComposeOnlyAsJson(
        path: Path,
        composeData: ComposeData,
        apiLevel: Int,
        processName: String
    ) {
        if (verbose) println("  Saving compose-only data...")

        val result = extractComposeFromInspectorData(composeData)
        val composeTree = result.first
        val composeStrings = result.second

        // Categorize strings
        val composeComposables = mutableListOf<String>()
        val composeSourceFiles = mutableListOf<String>()
        val textValues = mutableListOf<String>()

        for ((_, str) in composeStrings) {
            when {
                str.endsWith(".kt") || str.endsWith(".java") ->
                    composeSourceFiles.add(str)
                str.matches("^[A-Z][a-zA-Z0-9]*$".toRegex()) && str.length > 2 ->
                    composeComposables.add(str)
            }
        }

        // Extract text values from parameters
        if (composeData.parametersResponse != null) {
            val paramTextValues = extractTextFromParameters(composeData.parametersResponse)
            textValues.addAll(paramTextValues)
        }

        // Build JSON
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "metadata": {""")
        sb.appendLine("""    "processName": "${escapeJson(processName)}",""")
        sb.appendLine("""    "apiLevel": $apiLevel,""")
        sb.appendLine("""    "captureTime": "${java.time.Instant.now()}",""")
        sb.appendLine("""    "source": "LIVE_CAPTURE",""")
        sb.appendLine("""    "note": "Compose hierarchy only - no view layout captured"""")
        sb.appendLine("""  },""")

        // Compose hierarchy
        if (composeTree.isNotEmpty()) {
            sb.appendLine("""  "composeHierarchy": [""")
            composeTree.forEachIndexed { index, node ->
                sb.append(renderComposeNodeJson(node, "    ", index == composeTree.size - 1))
            }
            sb.appendLine("""  ],""")
        } else {
            sb.appendLine("""  "composeHierarchy": [],""")
        }

        // Composables list
        sb.appendLine("""  "composeComposables": [""")
        composeComposables.distinct().sorted().forEachIndexed { index, comp ->
            sb.append("""    "${escapeJson(comp)}"""")
            if (index < composeComposables.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Source files
        sb.appendLine("""  "sourceFiles": [""")
        composeSourceFiles.distinct().sorted().forEachIndexed { index, file ->
            sb.append("""    "${escapeJson(file)}"""")
            if (index < composeSourceFiles.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Text content
        sb.appendLine("""  "textContent": [""")
        textValues.distinct().sorted().forEachIndexed { index, text ->
            sb.append("""    "${escapeJson(text)}"""")
            if (index < textValues.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ]""")

        sb.appendLine("}")

        path.toFile().writeText(sb.toString())
        println("  Saved JSON with ${composeTree.size} compose nodes (compose-only)")
    }

    /**
     * Save raw layout event bytes using the same extraction logic as .li conversion.
     * If composeData is provided, use the actual Compose inspector data.
     */
    private fun saveRawLayoutAsJson(
        path: Path,
        rawBytes: ByteArray,
        apiLevel: Int,
        processName: String,
        composeData: ComposeData? = null
    ) {
        if (verbose) println("  Parsing raw layout data (${rawBytes.size} bytes)...")

        // Extract string tables from raw view layout bytes
        val allStringTables = extractAllStringTables(rawBytes)
        if (verbose) println("  Found ${allStringTables.size} string table entries from view layout")

        // Categorize strings from view layout
        val viewClasses = mutableListOf<String>()
        val composeComposables = mutableListOf<String>()
        val composeSourceFiles = mutableListOf<String>()
        val parameterNames = mutableListOf<String>()
        val textValues = mutableListOf<String>()

        for ((_, str) in allStringTables) {
            when {
                str.endsWith("View") || str.endsWith("Layout") || str.contains(".view.") || str.contains(".widget.") ->
                    viewClasses.add(str)
                str.endsWith(".kt") || str.endsWith(".java") ->
                    composeSourceFiles.add(str)
                str.matches("^[A-Z][a-zA-Z0-9]*$".toRegex()) && str.length > 2 ->
                    composeComposables.add(str)
                str.matches("^[a-z][a-zA-Z0-9]*$".toRegex()) ->
                    parameterNames.add(str)
                str.contains(" ") && str.length > 3 && str.all { it.code in 32..126 } ->
                    textValues.add(str)
            }
        }

        // Extract raw text values
        val rawTextValues = extractRawTextValues(rawBytes)
        textValues.addAll(rawTextValues)

        // Extract compose hierarchy - prefer actual Compose inspector data if available
        val composeTree: List<ParsedComposableNode>
        val composeStrings: Map<Int, String>

        if (composeData?.composablesResponse != null) {
            if (verbose) println("  Using Compose inspector data (${composeData.composablesResponse.stringsCount} strings, ${composeData.composablesResponse.rootsCount} roots)")
            val result = extractComposeFromInspectorData(composeData)
            composeTree = result.first
            composeStrings = result.second
            if (verbose) println("  Extracted ${composeTree.size} compose tree nodes")

            // Add compose-specific strings to source files and composables
            for ((_, str) in composeStrings) {
                when {
                    str.endsWith(".kt") || str.endsWith(".java") ->
                        composeSourceFiles.add(str)
                    str.matches("^[A-Z][a-zA-Z0-9]*$".toRegex()) && str.length > 2 ->
                        composeComposables.add(str)
                }
            }

            // Extract text values from parameters response
            if (verbose) {
                println("  Parameters response: ${if (composeData.parametersResponse != null) "present" else "NULL"}")
            }
            if (composeData.parametersResponse != null) {
                val paramTextValues = extractTextFromParameters(composeData.parametersResponse)
                textValues.addAll(paramTextValues)
                if (verbose) println("  Extracted ${paramTextValues.size} text values from parameters")
            }
        } else {
            // Fall back to extracting from raw bytes
            composeTree = extractComposeHierarchy(rawBytes, allStringTables)
            composeStrings = emptyMap()
        }

        // Parse the view tree from raw bytes
        // First extract LayoutEvent from Event wrapper if present (field 3 = layout_event)
        val layoutEventData = if (rawBytes.size > 4 && rawBytes[0].toInt().and(0xFF) == 0x1a) {
            // 0x1a = field 3, wireType 2 (length-delimited) = layout_event
            var pos = 1
            var length = 0L
            var shift = 0
            while (pos < rawBytes.size) {
                val b = rawBytes[pos].toInt() and 0xFF
                pos++
                length = length or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            if (verbose) println("  Extracted LayoutEvent: offset=$pos length=$length")
            rawBytes.sliceArray(pos until (pos + length.toInt()).coerceAtMost(rawBytes.size))
        } else {
            rawBytes
        }

        // Dump raw protobuf to file for analysis
        val dumpPath = path.parent.resolve("raw-layout-event.bin")
        dumpPath.toFile().writeBytes(layoutEventData)
        println("  Dumped raw LayoutEvent (${layoutEventData.size} bytes) to: $dumpPath")

        val viewTree = try {
            parseRawViewTree(layoutEventData)
        } catch (e: Exception) {
            if (verbose) println("  Warning: Failed to parse view tree: ${e.message}")
            null
        }
        if (verbose && viewTree != null) {
            println("  Parsed view tree: root=${viewTree.className}, children=${viewTree.children.size}")
        }

        // Build JSON
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "metadata": {""")
        sb.appendLine("""    "processName": "${escapeJson(processName)}",""")
        sb.appendLine("""    "apiLevel": $apiLevel,""")
        sb.appendLine("""    "captureTime": "${java.time.Instant.now()}",""")
        sb.appendLine("""    "source": "LIVE_CAPTURE"""")
        sb.appendLine("""  },""")

        // View tree with hierarchy, bounds, and text
        if (viewTree != null && viewTree.className.isNotEmpty()) {
            sb.appendLine("""  "viewTree":""")
            rawViewTreeToJson(viewTree, sb, "  ")
            sb.appendLine(",")
        }

        // Compose hierarchy
        if (composeTree.isNotEmpty()) {
            sb.appendLine("""  "composeHierarchy": [""")
            composeTree.forEachIndexed { index, node ->
                sb.append(renderComposeNodeJson(node, "    ", index == composeTree.size - 1))
            }
            sb.appendLine("""  ],""")
        }

        // View classes
        sb.appendLine("""  "viewClasses": [""")
        viewClasses.distinct().sorted().forEachIndexed { index, vc ->
            sb.append("""    "${escapeJson(vc)}"""")
            if (index < viewClasses.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Composables
        sb.appendLine("""  "composeComposables": [""")
        composeComposables.distinct().sorted().forEachIndexed { index, comp ->
            sb.append("""    "${escapeJson(comp)}"""")
            if (index < composeComposables.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Source files
        sb.appendLine("""  "sourceFiles": [""")
        composeSourceFiles.distinct().sorted().forEachIndexed { index, file ->
            sb.append("""    "${escapeJson(file)}"""")
            if (index < composeSourceFiles.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Text content
        sb.appendLine("""  "textContent": [""")
        textValues.distinct().sorted().forEachIndexed { index, text ->
            sb.append("""    "${escapeJson(text)}"""")
            if (index < textValues.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // String table
        sb.appendLine("""  "allStrings": {""")
        allStringTables.entries.sortedBy { it.key }.forEachIndexed { index, (id, str) ->
            sb.append("""    "$id": "${escapeJson(str)}"""")
            if (index < allStringTables.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  }""")

        sb.appendLine("}")

        path.toFile().writeText(sb.toString())
        println("  Saved JSON with ${composeTree.size} compose nodes, ${viewClasses.distinct().size} view classes")
    }

    private fun viewNodeToJson(
        node: com.android.tools.idea.layoutinspector.view.inspection.ViewNode,
        sb: StringBuilder,
        indent: String
    ) {
        sb.appendLine("$indent{")

        val nodeData = if (node.hasNode()) node.node else null

        if (nodeData != null) {
            sb.appendLine("""$indent  "id": ${nodeData.id},""")
            sb.appendLine("""$indent  "className": "${escapeJson(nodeData.className)}",""")

            if (nodeData.hasResource()) {
                val res = nodeData.resource
                sb.appendLine("""$indent  "resource": {""")
                sb.appendLine("""$indent    "name": "${escapeJson(res.name)}",""")
                sb.appendLine("""$indent    "type": ${res.type},""")
                sb.appendLine("""$indent    "namespace": ${res.namespace}""")
                sb.appendLine("""$indent  },""")
            }

            if (nodeData.hasBounds()) {
                val b = nodeData.bounds
                sb.appendLine("""$indent  "bounds": {"x": ${b.x}, "y": ${b.y}, "width": ${b.w}, "height": ${b.h}},""")
            }
        }

        sb.appendLine("""$indent  "childCount": ${node.childrenCount},""")
        sb.append("""$indent  "children": [""")

        if (node.childrenCount > 0) {
            sb.appendLine()
            node.childrenList.forEachIndexed { index, child ->
                viewNodeToJson(child, sb, "$indent    ")
                if (index < node.childrenCount - 1) {
                    sb.appendLine(",")
                } else {
                    sb.appendLine()
                }
            }
            sb.appendLine("$indent  ]")
        } else {
            sb.appendLine("]")
        }

        sb.append("$indent}")
    }

    private fun rawLayoutEventToJson(rawBytes: ByteArray, sb: StringBuilder, indent: String) {
        // Parse the raw protobuf to extract layout tree
        // This handles cases where the proto definition doesn't match exactly
        try {
            // Try to extract LayoutEvent from Event wrapper if present
            val layoutEventData = if (rawBytes.size > 4 && rawBytes[0].toInt().and(0xFF) == 0x1a) {
                var pos = 1
                var length = 0L
                var shift = 0
                while (pos < rawBytes.size) {
                    val b = rawBytes[pos].toInt() and 0xFF
                    pos++
                    length = length or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                rawBytes.sliceArray(pos until (pos + length.toInt()).coerceAtMost(rawBytes.size))
            } else {
                rawBytes
            }

            // Parse and convert to JSON
            val tree = parseRawViewTree(layoutEventData)
            rawViewTreeToJson(tree, sb, indent)
        } catch (e: Exception) {
            sb.appendLine("""$indent{"error": "Failed to parse raw layout: ${escapeJson(e.message ?: "unknown")}"}""")
        }
    }

    data class RawViewNode(
        val id: Long = 0,
        val className: String = "",
        val resourceName: String = "",
        val bounds: Bounds? = null,
        val children: MutableList<RawViewNode> = mutableListOf()
    )

    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun parseRawViewTree(data: ByteArray): RawViewNode {
        var pos = 0

        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos].toInt() and 0xFF
                pos++
                value = value or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return value
        }

        fun readBytes(length: Int): ByteArray {
            val result = data.sliceArray(pos until (pos + length).coerceAtMost(data.size))
            pos += length
            return result
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> {
                    val len = readVarint().toInt()
                    pos += len
                }
                5 -> pos += 4
            }
        }

        fun parseViewNodeData(nodeData: ByteArray): RawViewNode {
            var nodePos = 0
            var id = 0L
            var className = ""
            var resourceName = ""
            var bounds: Bounds? = null

            while (nodePos < nodeData.size) {
                val tag = nodeData[nodePos].toInt() and 0xFF
                nodePos++
                val field = tag shr 3
                val wire = tag and 0x7

                when {
                    field == 1 && wire == 0 -> {
                        // id (varint)
                        var v = 0L
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            v = v or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        id = v
                    }
                    field == 2 && wire == 2 -> {
                        // className (string/bytes)
                        var len = 0L
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            len = len or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        className = nodeData.sliceArray(nodePos until (nodePos + len.toInt()).coerceAtMost(nodeData.size))
                            .toString(Charsets.UTF_8)
                        nodePos += len.toInt()
                    }
                    field == 3 && wire == 2 -> {
                        // resource
                        var len = 0L
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            len = len or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        val resData = nodeData.sliceArray(nodePos until (nodePos + len.toInt()).coerceAtMost(nodeData.size))
                        // Parse resource.name (field 1)
                        if (resData.isNotEmpty() && (resData[0].toInt() and 0xFF) == 0x0a) {
                            var rp = 1
                            var rlen = 0L
                            var rs = 0
                            while (rp < resData.size) {
                                val b = resData[rp].toInt() and 0xFF
                                rp++
                                rlen = rlen or ((b and 0x7F).toLong() shl rs)
                                if ((b and 0x80) == 0) break
                                rs += 7
                            }
                            resourceName = resData.sliceArray(rp until (rp + rlen.toInt()).coerceAtMost(resData.size))
                                .toString(Charsets.UTF_8)
                        }
                        nodePos += len.toInt()
                    }
                    field == 4 && wire == 2 -> {
                        // bounds
                        var len = 0L
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            len = len or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        val boundsData = nodeData.sliceArray(nodePos until (nodePos + len.toInt()).coerceAtMost(nodeData.size))
                        // Parse bounds (x, y, w, h as fixed32 or varints)
                        var bx = 0; var by = 0; var bw = 0; var bh = 0
                        var bp = 0
                        while (bp < boundsData.size) {
                            val btag = boundsData[bp].toInt() and 0xFF
                            bp++
                            val bf = btag shr 3
                            val bwire = btag and 0x7
                            if (bwire == 0) {
                                var bv = 0L
                                var bs = 0
                                while (bp < boundsData.size) {
                                    val bb = boundsData[bp].toInt() and 0xFF
                                    bp++
                                    bv = bv or ((bb and 0x7F).toLong() shl bs)
                                    if ((bb and 0x80) == 0) break
                                    bs += 7
                                }
                                when (bf) {
                                    1 -> bx = bv.toInt()
                                    2 -> by = bv.toInt()
                                    3 -> bw = bv.toInt()
                                    4 -> bh = bv.toInt()
                                }
                            } else if (bwire == 5 && bp + 4 <= boundsData.size) {
                                val v = java.nio.ByteBuffer.wrap(boundsData, bp, 4)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                                bp += 4
                                when (bf) {
                                    1 -> bx = v
                                    2 -> by = v
                                    3 -> bw = v
                                    4 -> bh = v
                                }
                            }
                        }
                        bounds = Bounds(bx, by, bw, bh)
                        nodePos += len.toInt()
                    }
                    wire == 0 -> {
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                    }
                    wire == 2 -> {
                        var len = 0L
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            len = len or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        nodePos += len.toInt()
                    }
                    wire == 1 -> nodePos += 8
                    wire == 5 -> nodePos += 4
                    else -> break
                }
            }
            return RawViewNode(id, className, resourceName, bounds)
        }

        fun parseViewTree(treeData: ByteArray): RawViewNode {
            var treePos = 0
            var rootNode: RawViewNode? = null
            val children = mutableListOf<RawViewNode>()

            while (treePos < treeData.size) {
                val tag = treeData[treePos].toInt() and 0xFF
                treePos++
                val field = tag shr 3
                val wire = tag and 0x7

                if (field == 1 && wire == 2) {
                    // node or children (field 1)
                    var len = 0L
                    var s = 0
                    while (treePos < treeData.size) {
                        val b = treeData[treePos].toInt() and 0xFF
                        treePos++
                        len = len or ((b and 0x7F).toLong() shl s)
                        if ((b and 0x80) == 0) break
                        s += 7
                    }
                    val content = treeData.sliceArray(treePos until (treePos + len.toInt()).coerceAtMost(treeData.size))
                    treePos += len.toInt()

                    // First field 1 is the node data, subsequent ones are children (also ViewNode)
                    if (rootNode == null) {
                        rootNode = parseViewNodeData(content)
                    } else {
                        val child = parseViewTree(content)
                        children.add(child)
                    }
                } else if (wire == 0) {
                    var s = 0
                    while (treePos < treeData.size) {
                        val b = treeData[treePos].toInt() and 0xFF
                        treePos++
                        if ((b and 0x80) == 0) break
                        s += 7
                    }
                } else if (wire == 2) {
                    var len = 0L
                    var s = 0
                    while (treePos < treeData.size) {
                        val b = treeData[treePos].toInt() and 0xFF
                        treePos++
                        len = len or ((b and 0x7F).toLong() shl s)
                        if ((b and 0x80) == 0) break
                        s += 7
                    }
                    treePos += len.toInt()
                } else if (wire == 1) {
                    treePos += 8
                } else if (wire == 5) {
                    treePos += 4
                } else {
                    break
                }
            }

            return (rootNode ?: RawViewNode()).copy(children = children.toMutableList())
        }

        // Debug: show first few bytes and fields
        if (verbose && data.size > 10) {
            val hexPreview = data.take(20).joinToString(" ") { "%02x".format(it) }
            println("    LayoutEvent first 20 bytes: $hexPreview")
        }

        // Parse LayoutEvent structure:
        // field 1 = repeated StringEntry (string table)
        // field 2 = ViewNode root_view
        // field 3 = Screenshot
        val stringTable = mutableMapOf<Int, String>()

        while (pos < data.size) {
            val tag = data[pos].toInt() and 0xFF
            pos++
            val field = tag shr 3
            val wire = tag and 0x7

            if (verbose && (pos < 1000 || field in 2..4)) {
                println("    LayoutEvent: pos=${pos-1} tag=$tag (0x${tag.toString(16)}) field=$field wire=$wire")
            }

            when {
                field == 1 && wire == 2 -> {
                    // StringEntry - parse and add to string table
                    val startPos = pos - 1
                    val len = readVarint().toInt()
                    val entryData = readBytes(len)
                    if (verbose && stringTable.size >= 20) {
                        println("    Last StringEntry at pos=$startPos, len=$len, nextPos=$pos")
                    }
                    // Parse StringEntry { id = 1, str = 2 }
                    var entryPos = 0
                    var id = 0
                    var str = ""
                    while (entryPos < entryData.size) {
                        val entryTag = entryData[entryPos].toInt() and 0xFF
                        entryPos++
                        val entryField = entryTag shr 3
                        val entryWire = entryTag and 0x7
                        if (entryField == 1 && entryWire == 0) {
                            var v = 0
                            var s = 0
                            while (entryPos < entryData.size) {
                                val b = entryData[entryPos].toInt() and 0xFF
                                entryPos++
                                v = v or ((b and 0x7F) shl s)
                                if ((b and 0x80) == 0) break
                                s += 7
                            }
                            id = v
                        } else if (entryField == 2 && entryWire == 2) {
                            var slen = 0
                            var s = 0
                            while (entryPos < entryData.size) {
                                val b = entryData[entryPos].toInt() and 0xFF
                                entryPos++
                                slen = slen or ((b and 0x7F) shl s)
                                if ((b and 0x80) == 0) break
                                s += 7
                            }
                            str = entryData.sliceArray(entryPos until (entryPos + slen).coerceAtMost(entryData.size))
                                .toString(Charsets.UTF_8)
                            entryPos += slen
                        } else {
                            break
                        }
                    }
                    if (id > 0 && str.isNotEmpty()) {
                        stringTable[id] = str
                    }
                }
                field == 2 && wire == 2 -> {
                    // root_view (ViewNode) - minimal in API 36
                    val startPos = pos - 1
                    val len = readVarint().toInt()
                    if (verbose) {
                        println("    Found root_view at pos=$startPos: length=$len bytes (skipping)")
                    }
                    pos += len  // skip, real tree is in field 4
                }
                field == 4 && wire == 2 -> {
                    // app_context - contains the actual view tree in API 36+
                    val startPos = pos - 1
                    val len = readVarint().toInt()
                    if (verbose) {
                        println("    Found app_context at pos=$startPos: length=$len stringTable=${stringTable.size} entries")
                    }
                    val appContextData = readBytes(len)
                    return parseAppContextViewTree(appContextData, stringTable, verbose)
                }
                else -> {
                    skipField(wire)
                }
            }

            // Safety: don't parse more than needed (screenshot can be huge)
            if (pos > 2000000) {
                if (verbose) println("    Stopping parse - exceeded 2M bytes")
                break
            }
        }

        return RawViewNode()
    }

    /**
     * Parse view tree from app_context field (field 4 of LayoutEvent).
     * API 36+ uses this format where the actual view tree is in app_context.
     *
     * Structure discovered via protobuf analysis:
     * - field 2 = repeated children (ViewNode)
     * - field 4 = package name (string ID)
     * - field 5 = class name (string ID)
     * - field 6 = bounds (Rect: x, y, w, h)
     * - field 7 = resource (Resource: type, namespace, name)
     */
    private fun parseAppContextViewTree(data: ByteArray, stringTable: Map<Int, String>, verbose: Boolean): RawViewNode {
        var pos = 0

        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos].toInt() and 0xFF
                pos++
                value = value or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return value
        }

        fun readBytes(length: Int): ByteArray {
            val result = data.sliceArray(pos until (pos + length).coerceAtMost(data.size))
            pos += length
            return result
        }

        fun parseViewNode(nodeData: ByteArray, depth: Int = 0): RawViewNode {
            var nodePos = 0
            var id = 0L
            var className = ""
            var packageName = ""
            var resourceName = ""
            var bounds: Bounds? = null
            val children = mutableListOf<RawViewNode>()

            fun nodeReadVarint(): Long {
                var value = 0L
                var shift = 0
                while (nodePos < nodeData.size) {
                    val b = nodeData[nodePos].toInt() and 0xFF
                    nodePos++
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                return value
            }

            while (nodePos < nodeData.size) {
                val tag = nodeData[nodePos].toInt() and 0xFF
                nodePos++
                val field = tag shr 3
                val wire = tag and 0x7

                when {
                    field == 1 && wire == 0 -> {
                        // id (varint)
                        id = nodeReadVarint()
                    }
                    field == 2 && wire == 2 -> {
                        // child ViewNode (repeated)
                        val len = nodeReadVarint().toInt()
                        val childData = nodeData.sliceArray(nodePos until (nodePos + len).coerceAtMost(nodeData.size))
                        nodePos += len
                        children.add(parseViewNode(childData, depth + 1))
                    }
                    field == 4 && wire == 0 -> {
                        // package name (string ID)
                        val pkgId = nodeReadVarint().toInt()
                        packageName = stringTable[pkgId] ?: ""
                    }
                    field == 5 && wire == 0 -> {
                        // class name (string ID)
                        val classId = nodeReadVarint().toInt()
                        className = stringTable[classId] ?: "StringID:$classId"
                    }
                    field == 6 && wire == 2 -> {
                        // bounds (Rect message with nested size)
                        // Structure: field 1 = nested Size { field 3 = width, field 4 = height }
                        val len = nodeReadVarint().toInt()
                        val boundsData = nodeData.sliceArray(nodePos until (nodePos + len).coerceAtMost(nodeData.size))
                        nodePos += len
                        var x = 0; var y = 0; var w = 0; var h = 0
                        var bp = 0
                        while (bp < boundsData.size) {
                            val bTag = boundsData[bp].toInt() and 0xFF
                            bp++
                            val bField = bTag shr 3
                            val bWire = bTag and 0x7
                            when {
                                bWire == 0 -> {
                                    // Direct varint field
                                    var v = 0
                                    var bs = 0
                                    while (bp < boundsData.size) {
                                        val b = boundsData[bp].toInt() and 0xFF
                                        bp++
                                        v = v or ((b and 0x7F) shl bs)
                                        if ((b and 0x80) == 0) break
                                        bs += 7
                                    }
                                    when (bField) {
                                        1 -> x = v
                                        2 -> y = v
                                        3 -> w = v
                                        4 -> h = v
                                    }
                                }
                                bWire == 2 && bField == 1 -> {
                                    // Nested size message containing width/height
                                    var sizeLen = 0
                                    var bs = 0
                                    while (bp < boundsData.size) {
                                        val b = boundsData[bp].toInt() and 0xFF
                                        bp++
                                        sizeLen = sizeLen or ((b and 0x7F) shl bs)
                                        if ((b and 0x80) == 0) break
                                        bs += 7
                                    }
                                    val sizeEnd = bp + sizeLen
                                    while (bp < sizeEnd && bp < boundsData.size) {
                                        val sTag = boundsData[bp].toInt() and 0xFF
                                        bp++
                                        val sField = sTag shr 3
                                        val sWire = sTag and 0x7
                                        if (sWire == 0) {
                                            var v = 0
                                            var ss = 0
                                            while (bp < boundsData.size) {
                                                val b = boundsData[bp].toInt() and 0xFF
                                                bp++
                                                v = v or ((b and 0x7F) shl ss)
                                                if ((b and 0x80) == 0) break
                                                ss += 7
                                            }
                                            when (sField) {
                                                3 -> w = v
                                                4 -> h = v
                                            }
                                        }
                                    }
                                }
                                else -> break
                            }
                        }
                        bounds = Bounds(x, y, w, h)
                    }
                    field == 7 && wire == 2 -> {
                        // resource (Resource message)
                        val len = nodeReadVarint().toInt()
                        val resData = nodeData.sliceArray(nodePos until (nodePos + len).coerceAtMost(nodeData.size))
                        nodePos += len
                        // Parse Resource - name is field 3 (string ID in this context)
                        var rp = 0
                        while (rp < resData.size) {
                            val rTag = resData[rp].toInt() and 0xFF
                            rp++
                            val rField = rTag shr 3
                            val rWire = rTag and 0x7
                            when {
                                rField == 3 && rWire == 0 -> {
                                    // name as string ID
                                    var v = 0
                                    var rs = 0
                                    while (rp < resData.size) {
                                        val b = resData[rp].toInt() and 0xFF
                                        rp++
                                        v = v or ((b and 0x7F) shl rs)
                                        if ((b and 0x80) == 0) break
                                        rs += 7
                                    }
                                    resourceName = stringTable[v] ?: "@$v"
                                }
                                rField == 3 && rWire == 2 -> {
                                    // name as string literal
                                    var nameLen = 0
                                    var rs = 0
                                    while (rp < resData.size) {
                                        val b = resData[rp].toInt() and 0xFF
                                        rp++
                                        nameLen = nameLen or ((b and 0x7F) shl rs)
                                        if ((b and 0x80) == 0) break
                                        rs += 7
                                    }
                                    resourceName = resData.sliceArray(rp until (rp + nameLen).coerceAtMost(resData.size))
                                        .toString(Charsets.UTF_8)
                                    rp += nameLen
                                }
                                rWire == 0 -> {
                                    // skip varint
                                    while (rp < resData.size) {
                                        val b = resData[rp].toInt() and 0xFF
                                        rp++
                                        if ((b and 0x80) == 0) break
                                    }
                                }
                                rWire == 2 -> {
                                    // skip length-delimited
                                    var len2 = 0
                                    var rs = 0
                                    while (rp < resData.size) {
                                        val b = resData[rp].toInt() and 0xFF
                                        rp++
                                        len2 = len2 or ((b and 0x7F) shl rs)
                                        if ((b and 0x80) == 0) break
                                        rs += 7
                                    }
                                    rp += len2
                                }
                                else -> break
                            }
                        }
                    }
                    wire == 0 -> {
                        // Skip varint
                        nodeReadVarint()
                    }
                    wire == 2 -> {
                        // Skip length-delimited
                        val len = nodeReadVarint().toInt()
                        nodePos += len
                    }
                    wire == 1 -> nodePos += 8
                    wire == 5 -> nodePos += 4
                    else -> break
                }
            }

            // Combine package and class name if we have both
            val fullClassName = if (packageName.isNotEmpty() && className.isNotEmpty()) {
                "$className ($packageName)"
            } else {
                className
            }

            return RawViewNode(id, fullClassName, resourceName, bounds, children)
        }

        // The app_context itself is a ViewNode
        val result = parseViewNode(data)
        if (verbose) {
            fun countNodes(node: RawViewNode): Int = 1 + node.children.sumOf { countNodes(it) }
            println("    Parsed view tree: ${countNodes(result)} nodes")
        }
        return result
    }

    private fun parseViewTreeWithStringTable(data: ByteArray, stringTable: Map<Int, String>): RawViewNode {
        var pos = 0

        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos].toInt() and 0xFF
                pos++
                value = value or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return value
        }

        fun readBytes(length: Int): ByteArray {
            val result = data.sliceArray(pos until (pos + length).coerceAtMost(data.size))
            pos += length
            return result
        }

        // ViewNodeData structure (uses string IDs from string table):
        // field 1 = int64 id
        // field 2 = int32 class_name (string ID)
        // field 3 = int32 package_name (string ID)
        // field 4 = Resource resource
        // field 5 = Rect bounds
        fun parseViewNodeData(nodeData: ByteArray): RawViewNode {
            var nodePos = 0
            var id = 0L
            var className = ""
            var resourceName = ""
            var bounds: Bounds? = null

            while (nodePos < nodeData.size) {
                val tag = nodeData[nodePos].toInt() and 0xFF
                nodePos++
                val field = tag shr 3
                val wire = tag and 0x7

                when {
                    field == 1 && wire == 0 -> {
                        // id (varint)
                        var v = 0L
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            v = v or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        id = v
                    }
                    field == 2 && wire == 0 -> {
                        // class_name as string ID (varint)
                        var v = 0
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            v = v or ((b and 0x7F) shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        className = stringTable[v] ?: "StringID:$v"
                    }
                    field == 4 && wire == 2 -> {
                        // resource (Resource message: type=1, namespace=2, name=3)
                        var len = 0
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            len = len or ((b and 0x7F) shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        val resData = nodeData.sliceArray(nodePos until (nodePos + len).coerceAtMost(nodeData.size))
                        // Parse Resource - name is field 3 (string)
                        var rp = 0
                        while (rp < resData.size) {
                            val rTag = resData[rp].toInt() and 0xFF
                            rp++
                            val rField = rTag shr 3
                            val rWire = rTag and 0x7
                            when {
                                rField == 3 && rWire == 2 -> {
                                    // name (string)
                                    var nameLen = 0
                                    var rs = 0
                                    while (rp < resData.size) {
                                        val b = resData[rp].toInt() and 0xFF
                                        rp++
                                        nameLen = nameLen or ((b and 0x7F) shl rs)
                                        if ((b and 0x80) == 0) break
                                        rs += 7
                                    }
                                    resourceName = resData.sliceArray(rp until (rp + nameLen).coerceAtMost(resData.size))
                                        .toString(Charsets.UTF_8)
                                    rp += nameLen
                                }
                                rWire == 0 -> {
                                    // skip varint
                                    while (rp < resData.size) {
                                        val b = resData[rp].toInt() and 0xFF
                                        rp++
                                        if ((b and 0x80) == 0) break
                                    }
                                }
                                else -> break
                            }
                        }
                        nodePos += len
                    }
                    field == 5 && wire == 2 -> {
                        // bounds (Rect message: x=1, y=2, w=3, h=4 - all int32)
                        var len = 0
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            len = len or ((b and 0x7F) shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        val boundsData = nodeData.sliceArray(nodePos until (nodePos + len).coerceAtMost(nodeData.size))
                        var x = 0; var y = 0; var w = 0; var h = 0
                        var bp = 0
                        while (bp < boundsData.size) {
                            val bTag = boundsData[bp].toInt() and 0xFF
                            bp++
                            val bField = bTag shr 3
                            val bWire = bTag and 0x7
                            if (bWire == 0) {
                                var v = 0
                                var bs = 0
                                while (bp < boundsData.size) {
                                    val b = boundsData[bp].toInt() and 0xFF
                                    bp++
                                    v = v or ((b and 0x7F) shl bs)
                                    if ((b and 0x80) == 0) break
                                    bs += 7
                                }
                                when (bField) {
                                    1 -> x = v
                                    2 -> y = v
                                    3 -> w = v
                                    4 -> h = v
                                }
                            } else {
                                break
                            }
                        }
                        bounds = Bounds(x, y, w, h)
                        nodePos += len
                    }
                    wire == 0 -> {
                        // Skip varint
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            if ((b and 0x80) == 0) break
                        }
                    }
                    wire == 2 -> {
                        // Skip length-delimited
                        var len = 0L
                        var s = 0
                        while (nodePos < nodeData.size) {
                            val b = nodeData[nodePos].toInt() and 0xFF
                            nodePos++
                            len = len or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        nodePos += len.toInt()
                    }
                    wire == 1 -> nodePos += 8
                    wire == 5 -> nodePos += 4
                    else -> break
                }
            }
            return RawViewNode(id, className, resourceName, bounds)
        }

        // ViewNode structure:
        // field 1 = ViewNodeData (node)
        // field 2 = repeated ViewNode (children)
        fun parseViewTree(treeData: ByteArray): RawViewNode {
            var treePos = 0
            var rootNode: RawViewNode? = null
            val children = mutableListOf<RawViewNode>()

            while (treePos < treeData.size) {
                val tag = treeData[treePos].toInt() and 0xFF
                treePos++
                val field = tag shr 3
                val wire = tag and 0x7

                when {
                    field == 1 && wire == 2 -> {
                        // ViewNodeData (node)
                        var len = 0L
                        var s = 0
                        while (treePos < treeData.size) {
                            val b = treeData[treePos].toInt() and 0xFF
                            treePos++
                            len = len or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        val content = treeData.sliceArray(treePos until (treePos + len.toInt()).coerceAtMost(treeData.size))
                        treePos += len.toInt()
                        rootNode = parseViewNodeData(content)
                    }
                    field == 2 && wire == 2 -> {
                        // Child ViewNode
                        var len = 0L
                        var s = 0
                        while (treePos < treeData.size) {
                            val b = treeData[treePos].toInt() and 0xFF
                            treePos++
                            len = len or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        val content = treeData.sliceArray(treePos until (treePos + len.toInt()).coerceAtMost(treeData.size))
                        treePos += len.toInt()
                        children.add(parseViewTree(content))
                    }
                    wire == 0 -> {
                        // Skip varint
                        while (treePos < treeData.size) {
                            val b = treeData[treePos].toInt() and 0xFF
                            treePos++
                            if ((b and 0x80) == 0) break
                        }
                    }
                    wire == 2 -> {
                        // Skip length-delimited
                        var len = 0L
                        var s = 0
                        while (treePos < treeData.size) {
                            val b = treeData[treePos].toInt() and 0xFF
                            treePos++
                            len = len or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                        }
                        treePos += len.toInt()
                    }
                    wire == 1 -> treePos += 8
                    wire == 5 -> treePos += 4
                    else -> break
                }
            }

            return (rootNode ?: RawViewNode()).copy(children = children.toMutableList())
        }

        return parseViewTree(data)
    }

    private fun rawViewTreeToJson(node: RawViewNode, sb: StringBuilder, indent: String) {
        sb.appendLine("$indent{")
        sb.appendLine("""$indent  "id": ${node.id},""")
        sb.appendLine("""$indent  "className": "${escapeJson(node.className)}",""")
        if (node.resourceName.isNotEmpty()) {
            sb.appendLine("""$indent  "resourceName": "${escapeJson(node.resourceName)}",""")
        }
        if (node.bounds != null) {
            sb.appendLine("""$indent  "bounds": {"x": ${node.bounds.x}, "y": ${node.bounds.y}, "width": ${node.bounds.width}, "height": ${node.bounds.height}},""")
        }
        sb.appendLine("""$indent  "childCount": ${node.children.size},""")
        sb.append("""$indent  "children": [""")

        if (node.children.isNotEmpty()) {
            sb.appendLine()
            node.children.forEachIndexed { index, child ->
                rawViewTreeToJson(child, sb, "$indent    ")
                if (index < node.children.size - 1) {
                    sb.appendLine(",")
                } else {
                    sb.appendLine()
                }
            }
            sb.appendLine("$indent  ]")
        } else {
            sb.appendLine("]")
        }

        sb.append("$indent}")
    }

    private fun composeDataToJson(composeData: ComposeData, sb: StringBuilder, indent: String) {
        val response = composeData.composablesResponse
        if (response == null) {
            sb.appendLine("""$indent{"error": "No compose data"}""")
            return
        }

        sb.appendLine("$indent{")
        sb.appendLine("""$indent  "viewId": ${composeData.viewId},""")
        sb.appendLine("""$indent  "rootCount": ${response.rootsCount},""")
        sb.appendLine("""$indent  "stringCount": ${response.stringsCount},""")

        // Build string table
        val strings = response.stringsList.associate { it.id to it.str }

        sb.appendLine("""$indent  "roots": [""")
        response.rootsList.forEachIndexed { index, root ->
            composeRootToJson(root, strings, sb, "$indent    ")
            if (index < response.rootsCount - 1) sb.appendLine(",") else sb.appendLine()
        }
        sb.appendLine("$indent  ]")
        sb.append("$indent}")
    }

    private fun composeRootToJson(
        root: layoutinspector.compose.inspection.ComposableRoot,
        strings: Map<Int, String>,
        sb: StringBuilder,
        indent: String
    ) {
        sb.appendLine("$indent{")
        sb.appendLine("""$indent  "viewId": ${root.viewId},""")
        sb.appendLine("""$indent  "nodeCount": ${root.nodesCount},""")
        sb.append("""$indent  "nodes": [""")

        if (root.nodesCount > 0) {
            sb.appendLine()
            root.nodesList.forEachIndexed { index, node ->
                composeNodeToJson(node, strings, sb, "$indent    ")
                if (index < root.nodesCount - 1) sb.appendLine(",") else sb.appendLine()
            }
            sb.appendLine("$indent  ]")
        } else {
            sb.appendLine("]")
        }

        sb.append("$indent}")
    }

    private fun composeNodeToJson(
        node: layoutinspector.compose.inspection.ComposableNode,
        strings: Map<Int, String>,
        sb: StringBuilder,
        indent: String
    ) {
        sb.appendLine("$indent{")
        sb.appendLine("""$indent  "id": ${node.id},""")
        sb.appendLine("""$indent  "name": "${escapeJson(strings[node.name] ?: "unknown")}",""")
        sb.appendLine("""$indent  "filename": "${escapeJson(strings[node.filename] ?: "")}",""")
        sb.appendLine("""$indent  "packageHash": ${node.packageHash},""")
        sb.appendLine("""$indent  "offset": ${node.offset},""")
        sb.appendLine("""$indent  "lineNumber": ${node.lineNumber},""")
        sb.appendLine("""$indent  "flags": ${node.flags},""")

        if (node.hasBounds()) {
            val b = node.bounds
            val boundsStr = if (b.hasLayout()) {
                val l = b.layout
                """{"x": ${l.x}, "y": ${l.y}, "w": ${l.w}, "h": ${l.h}}"""
            } else if (b.hasRender()) {
                val r = b.render
                """{"x0": ${r.x0}, "y0": ${r.y0}, "x1": ${r.x1}, "y1": ${r.y1}, "x2": ${r.x2}, "y2": ${r.y2}, "x3": ${r.x3}, "y3": ${r.y3}}"""
            } else {
                """{}"""
            }
            sb.appendLine("""$indent  "bounds": $boundsStr,""")
        }

        sb.appendLine("""$indent  "childCount": ${node.childrenCount},""")
        sb.append("""$indent  "children": [""")

        if (node.childrenCount > 0) {
            sb.appendLine()
            node.childrenList.forEachIndexed { index, child ->
                composeNodeToJson(child, strings, sb, "$indent    ")
                if (index < node.childrenCount - 1) sb.appendLine(",") else sb.appendLine()
            }
            sb.appendLine("$indent  ]")
        } else {
            sb.appendLine("]")
        }

        sb.append("$indent}")
    }

    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun saveSnapshotFromEvents(
        path: Path,
        captureResult: LayoutCaptureResult,
        apiLevel: Int,
        processName: String
    ) {
        val layoutEvent = captureResult.layoutEvent
        val rawLayoutBytes = captureResult.rawLayoutEventBytes
        val composeData = captureResult.composeData
        val hasCompose = composeData?.composablesResponse != null

        // Default configuration values
        val dpi = 420
        val fontScale = 1.0f
        val screenWidth = 1080
        val screenHeight = 2400

        val metadata = Metadata.newBuilder()
            .setApiLevel(apiLevel)
            .setProcessName(processName)
            .setContainsCompose(hasCompose)
            .setLiveDuringCapture(false)
            .setSource(Metadata.Source.CLI)
            .setSourceVersion("1.0.0")
            .setDpi(dpi)
            .setFontScale(fontScale)
            .setScreenWidth(screenWidth)
            .setScreenHeight(screenHeight)
            .build()

        // Write in Android Studio compatible format (Version 4):
        // Java ObjectOutputStream format with TC_BLOCKDATALONG blocks:
        // - Magic: 0xaced (STREAM_MAGIC)
        // - Version: 0x0005 (STREAM_VERSION)
        // - TC_BLOCKDATALONG (0x7a) + 4-byte length + data
        // Data inside the block (matches ObjectOutputStream.writeUTF + writeDelimitedTo):
        // - 2 bytes BE: JSON length (UTF format)
        // - JSON content: {"version":"4","title":"processName"}
        // - Metadata proto (length-delimited: varint length + proto bytes)
        // - Snapshot proto (length-delimited: varint length + proto bytes)

        // First, build the Snapshot proto bytes
        val snapshotBytes: ByteArray
        var layoutDataSize = 0

        if (rawLayoutBytes != null) {
            // The raw bytes contain: Event { LayoutEvent layout_event = 3 }
            // We need: Snapshot { CaptureSnapshotResponse view_snapshot = 1 { WindowSnapshot ws = 1 { LayoutEvent layout = 1 } } }

            // Extract LayoutEvent from the raw Event wrapper
            // First bytes: 1a f7 cb 63 = field 3, length varint
            val layoutEventData = if (rawLayoutBytes.size > 4 && rawLayoutBytes[0].toInt() == 0x1a) {
                // Skip the Event wrapper (field 3 header and length)
                var pos = 1
                var length = 0
                var shift = 0
                while (pos < rawLayoutBytes.size) {
                    val b = rawLayoutBytes[pos].toInt() and 0xFF
                    pos++
                    length = length or ((b and 0x7F) shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                rawLayoutBytes.sliceArray(pos until pos + length)
            } else {
                rawLayoutBytes
            }
            layoutDataSize = layoutEventData.size

            // Extract root node ID from LayoutEvent
            // Structure: LayoutEvent.root_view(1).node(1).id(1)
            // Path: 0x0a <len> 0x0a <len> 0x08 <id_varint>
            val rootNodeId = extractRootNodeId(layoutEventData)
            println("  Extracted root node ID: $rootNodeId")

            // Now build the proper Snapshot structure
            val outputStream = java.io.ByteArrayOutputStream()

            // WindowSnapshot.layout = field 1 (the LayoutEvent bytes)
            writeVarint(outputStream, (1 shl 3) or 2)  // field 1, wire type 2
            writeVarint(outputStream, layoutEventData.size)
            outputStream.write(layoutEventData)
            val windowSnapshotBytes = outputStream.toByteArray()
            outputStream.reset()

            // CaptureSnapshotResponse.window_snapshots = field 1 (repeated, one WindowSnapshot)
            writeVarint(outputStream, (1 shl 3) or 2)  // field 1, wire type 2
            writeVarint(outputStream, windowSnapshotBytes.size)
            outputStream.write(windowSnapshotBytes)

            // CaptureSnapshotResponse.window_roots = field 2 (WindowRootsEvent)
            // WindowRootsEvent.ids = field 1 (repeated int64) - must match rootView.node.id!
            val windowRootsStream = java.io.ByteArrayOutputStream()
            writeVarint(windowRootsStream, (1 shl 3) or 0)  // field 1, wire type 0 (varint)
            writeVarint64(windowRootsStream, rootNodeId)
            val windowRootsBytes = windowRootsStream.toByteArray()

            writeVarint(outputStream, (2 shl 3) or 2)  // field 2, wire type 2
            writeVarint(outputStream, windowRootsBytes.size)
            outputStream.write(windowRootsBytes)

            val captureResponseBytes = outputStream.toByteArray()
            outputStream.reset()

            // Snapshot.view_snapshot = field 1 (CaptureSnapshotResponse)
            writeVarint(outputStream, (1 shl 3) or 2)  // field 1, wire type 2
            writeVarint(outputStream, captureResponseBytes.size)
            outputStream.write(captureResponseBytes)

            // Add Compose data if available (Snapshot.compose_info = field 2)
            if (composeData?.composablesResponse != null) {
                // Build ComposeInfo message
                val composeInfoStream = java.io.ByteArrayOutputStream()

                // ComposeInfo.view_id = field 1 (int64)
                writeVarint(composeInfoStream, (1 shl 3) or 0)  // field 1, wire type 0 (varint)
                writeVarint64(composeInfoStream, composeData.viewId)

                // ComposeInfo.composables = field 2 (GetComposablesResponse)
                val composablesBytes = composeData.composablesResponse.toByteArray()
                writeVarint(composeInfoStream, (2 shl 3) or 2)  // field 2, wire type 2
                writeVarint(composeInfoStream, composablesBytes.size)
                composeInfoStream.write(composablesBytes)

                // ComposeInfo.compose_parameters = field 3 (GetAllParametersResponse)
                if (composeData.parametersResponse != null) {
                    val paramsBytes = composeData.parametersResponse.toByteArray()
                    writeVarint(composeInfoStream, (3 shl 3) or 2)  // field 3, wire type 2
                    writeVarint(composeInfoStream, paramsBytes.size)
                    composeInfoStream.write(paramsBytes)
                }

                val composeInfoBytes = composeInfoStream.toByteArray()

                // Snapshot.compose_info = field 2 (repeated ComposeInfo)
                writeVarint(outputStream, (2 shl 3) or 2)  // field 2, wire type 2
                writeVarint(outputStream, composeInfoBytes.size)
                outputStream.write(composeInfoBytes)

                println("  Added Compose data: viewId=${composeData.viewId}")
            }

            snapshotBytes = outputStream.toByteArray()
        } else if (layoutEvent != null) {
            // Build WindowSnapshot with LayoutEvent inside
            val windowSnapshotBuilder = com.android.tools.idea.layoutinspector.view.inspection.CaptureSnapshotResponse.WindowSnapshot.newBuilder()
                .setLayout(layoutEvent)

            // Build CaptureSnapshotResponse
            val captureSnapshotResponseBuilder = com.android.tools.idea.layoutinspector.view.inspection.CaptureSnapshotResponse.newBuilder()
                .addWindowSnapshots(windowSnapshotBuilder.build())

            // Add window roots if available
            if (captureResult.windowRoots != null) {
                captureSnapshotResponseBuilder.setWindowRoots(captureResult.windowRoots)
            }

            val viewSnapshot = captureSnapshotResponseBuilder.build()
            val snapshotBuilder = Snapshot.newBuilder()
                .setViewSnapshot(viewSnapshot)

            // Add Compose data if available
            if (composeData?.composablesResponse != null) {
                val composeInfo = Snapshot.ComposeInfo.newBuilder()
                    .setViewId(composeData.viewId)
                    .setComposables(composeData.composablesResponse)
                if (composeData.parametersResponse != null) {
                    composeInfo.setComposeParameters(composeData.parametersResponse)
                }
                snapshotBuilder.addComposeInfo(composeInfo.build())
                println("  Added Compose data: viewId=${composeData.viewId}")
            }

            snapshotBytes = snapshotBuilder.build().toByteArray()
            layoutDataSize = layoutEvent.serializedSize
        } else {
            snapshotBytes = ByteArray(0)
        }

        // Build the entire data block content
        val dataBlock = java.io.ByteArrayOutputStream()
        val dataOutput = java.io.DataOutputStream(dataBlock)

        // Write JSON header (2-byte length prefix, same as Java's writeUTF format)
        val versionJson = """{"version":"4","title":"$processName"}"""
        dataOutput.writeShort(versionJson.length)
        dataOutput.writeBytes(versionJson)

        // Note: Version 4 format has NO 'S' marker - metadata follows directly after JSON

        // Write metadata (length-delimited: varint length prefix + proto bytes)
        val metadataBytes = metadata.toByteArray()
        writeVarint(dataBlock, metadataBytes.size)
        dataBlock.write(metadataBytes)

        // Write Snapshot (length-delimited: varint length prefix + proto bytes)
        writeVarint(dataBlock, snapshotBytes.size)
        dataBlock.write(snapshotBytes)

        // Now write the file with ObjectOutputStream wrapper
        FileOutputStream(path.toFile()).use { fos ->
            java.io.DataOutputStream(fos).use { dos ->
                // Write ObjectOutputStream header
                dos.writeShort(0xaced.toInt())  // STREAM_MAGIC
                dos.writeShort(0x0005)          // STREAM_VERSION

                // Write TC_BLOCKDATALONG block
                val blockData = dataBlock.toByteArray()
                dos.writeByte(0x7a)             // TC_BLOCKDATALONG
                dos.writeInt(blockData.size)   // 4-byte length
                dos.write(blockData)           // data
            }
        }

        println("  Saved $layoutDataSize bytes of layout data")
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private fun writeVarint64(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    /**
     * Extract the root node ID from raw LayoutEvent bytes.
     * Structure: LayoutEvent.root_view(field 1).node(field 1).id(field 1)
     * Proto path: 0x0a <len> 0x0a <len> 0x08 <id_varint>
     */
    private fun extractRootNodeId(layoutEventData: ByteArray): Long {
        try {
            var pos = 0

            // Helper to read varint
            fun readVarint(): Long {
                var value = 0L
                var shift = 0
                while (pos < layoutEventData.size) {
                    val b = layoutEventData[pos].toInt() and 0xFF
                    pos++
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                return value
            }

            // Helper to skip a field based on wire type
            fun skipField(wireType: Int) {
                when (wireType) {
                    0 -> readVarint()  // varint
                    1 -> pos += 8      // 64-bit
                    2 -> pos += readVarint().toInt()  // length-delimited
                    5 -> pos += 4      // 32-bit
                }
            }

            // Parse LayoutEvent looking for field 1 (root_view)
            while (pos < layoutEventData.size) {
                val tag = layoutEventData[pos].toInt() and 0xFF
                pos++
                val fieldNum = tag shr 3
                val wireType = tag and 0x7

                if (fieldNum == 1 && wireType == 2) {
                    // Found root_view (ViewNode), read its length
                    val rootViewLen = readVarint().toInt()
                    val rootViewEnd = pos + rootViewLen

                    // Parse ViewNode looking for field 1 (node)
                    while (pos < rootViewEnd) {
                        val tag2 = layoutEventData[pos].toInt() and 0xFF
                        pos++
                        val fieldNum2 = tag2 shr 3
                        val wireType2 = tag2 and 0x7

                        if (fieldNum2 == 1 && wireType2 == 2) {
                            // Found node (ViewNodeData), read its length
                            val nodeLen = readVarint().toInt()
                            val nodeEnd = pos + nodeLen

                            // Parse ViewNodeData looking for field 1 (id)
                            while (pos < nodeEnd) {
                                val tag3 = layoutEventData[pos].toInt() and 0xFF
                                pos++
                                val fieldNum3 = tag3 shr 3
                                val wireType3 = tag3 and 0x7

                                if (fieldNum3 == 1 && wireType3 == 0) {
                                    // Found id field!
                                    return readVarint()
                                } else {
                                    skipField(wireType3)
                                }
                            }
                        } else {
                            skipField(wireType2)
                        }
                    }
                } else {
                    skipField(wireType)
                }
            }
        } catch (e: Exception) {
            println("  Warning: Failed to extract root node ID: ${e.message}")
        }
        return 1L  // Fallback to 1
    }

    private fun countNodes(node: com.android.tools.idea.layoutinspector.view.inspection.ViewNode): Int {
        return 1 + node.childrenList.sumOf { countNodes(it) }
    }

    private fun convertLiToJson(inputFile: File): Int {
        if (!inputFile.exists()) {
            System.err.println("Error: File not found: ${inputFile.absolutePath}")
            return 1
        }

        if (verbose) println("Converting ${inputFile.name} to JSON...")

        try {
            java.io.DataInputStream(java.io.FileInputStream(inputFile)).use { dis ->
                // Android Studio snapshots use Java ObjectOutputStream format:
                // - 4 bytes: Java serialization magic (ac ed 00 05)
                // - 1 byte: TC_BLOCKDATALONG (0x7a)
                // - 4 bytes: block length
                // - 2 bytes: JSON header length (short)
                // - JSON header string
                // - protobuf messages

                // Android Studio snapshots use Java ObjectOutputStream format.
                // We need to read all block data, skipping the serialization overhead.

                // Read and verify Java serialization magic
                val magic = dis.readShort().toInt() and 0xFFFF
                val version = dis.readShort().toInt() and 0xFFFF
                if (magic != 0xACED || version != 0x0005) {
                    System.err.println("Error: Not a valid Java serialization file (magic=$magic, version=$version)")
                    return 1
                }
                if (verbose) println("  Java serialization stream detected")

                // Read all block data into a buffer (handles multiple TC_BLOCKDATA/LONG blocks)
                val allData = java.io.ByteArrayOutputStream()
                while (true) {
                    val tcType = dis.read()
                    if (tcType == -1) break // EOF
                    when (tcType) {
                        0x77 -> { // TC_BLOCKDATA (short)
                            val len = dis.read() and 0xFF
                            val data = ByteArray(len)
                            dis.readFully(data)
                            allData.write(data)
                        }
                        0x7A -> { // TC_BLOCKDATALONG
                            val len = dis.readInt()
                            val data = ByteArray(len)
                            dis.readFully(data)
                            allData.write(data)
                        }
                        0x78 -> break // TC_ENDBLOCKDATA
                        else -> {
                            if (verbose) println("  Unknown TC type: 0x${tcType.toString(16)}, stopping")
                            break
                        }
                    }
                }

                val blockData = allData.toByteArray()
                if (verbose) println("  Total block data: ${blockData.size} bytes")

                // Now parse the block data
                val blockStream = java.io.DataInputStream(java.io.ByteArrayInputStream(blockData))

                // Read JSON header length (2 bytes) - it's a short
                val versionJsonLen = blockStream.readShort().toInt() and 0xFFFF
                if (verbose) println("  Version header length: $versionJsonLen")

                val versionJsonBytes = ByteArray(versionJsonLen)
                blockStream.readFully(versionJsonBytes)
                val versionJson = String(versionJsonBytes)
                if (verbose) println("  Version: $versionJson")

                // Wrap remaining data for protobuf parsing
                val remainingData = blockData.copyOfRange(2 + versionJsonLen, blockData.size)
                val protoStream = java.io.ByteArrayInputStream(remainingData)

                // Read metadata proto (delimited)
                val metadata = Metadata.parseDelimitedFrom(protoStream)
                if (verbose) {
                    println("  Metadata:")
                    println("    API Level: ${metadata.apiLevel}")
                    println("    Process: ${metadata.processName}")
                    println("    Contains Compose: ${metadata.containsCompose}")
                    println("    Source: ${metadata.source}")
                    println("    Source Version: ${metadata.sourceVersion}")
                }

                // Read snapshot proto (delimited)
                // Try parsing with typed proto first, fall back to raw extraction if it fails
                val snapshot: Snapshot? = try {
                    Snapshot.parseDelimitedFrom(protoStream)
                } catch (e: Exception) {
                    if (verbose) println("  Warning: Typed proto parsing failed (${e.message}), using raw extraction")
                    null
                }

                if (snapshot != null) {
                    if (verbose) {
                        println("  Snapshot loaded:")
                        println("    Has viewSnapshot: ${snapshot.hasViewSnapshot()}")
                        println("    Compose info count: ${snapshot.composeInfoCount}")
                    }
                } else {
                    // Fall back to raw extraction for incompatible files
                    return convertLiToJsonRaw(versionJson, metadata, remainingData, outputFile ?: File(inputFile.nameWithoutExtension + ".json"))
                }

                // Build JSON output
                val sb = StringBuilder()
                sb.appendLine("{")
                sb.appendLine("""  "version": ${versionJson.replace("{", "").replace("}", "")},""")
                sb.appendLine("""  "metadata": {""")
                sb.appendLine("""    "apiLevel": ${metadata.apiLevel},""")
                sb.appendLine("""    "processName": "${escapeJson(metadata.processName)}",""")
                sb.appendLine("""    "containsCompose": ${metadata.containsCompose},""")
                sb.appendLine("""    "source": "${metadata.source}",""")
                sb.appendLine("""    "sourceVersion": "${escapeJson(metadata.sourceVersion)}",""")
                sb.appendLine("""    "dpi": ${metadata.dpi},""")
                sb.appendLine("""    "fontScale": ${metadata.fontScale},""")
                sb.appendLine("""    "screenWidth": ${metadata.screenWidth},""")
                sb.appendLine("""    "screenHeight": ${metadata.screenHeight}""")
                sb.appendLine("""  },""")

                // View hierarchy
                if (snapshot.hasViewSnapshot()) {
                    val viewSnapshot = snapshot.viewSnapshot
                    sb.appendLine("""  "viewHierarchy": {""")

                    // Window roots (just IDs)
                    if (viewSnapshot.hasWindowRoots()) {
                        sb.appendLine("""    "windowRootIds": ${viewSnapshot.windowRoots.idsList},""")
                    }

                    // Window snapshots (view trees)
                    if (viewSnapshot.windowSnapshotsCount > 0) {
                        sb.appendLine("""    "windows": [""")
                        viewSnapshot.windowSnapshotsList.forEachIndexed { snapshotIndex, windowSnapshot ->
                            sb.appendLine("""      {""")
                            if (windowSnapshot.hasLayout()) {
                                val layoutEvent = windowSnapshot.layout
                                // Build string table map
                                val stringTable = layoutEvent.stringEntriesList.associate { it.id to it.str }

                                if (layoutEvent.hasRootView()) {
                                    val rootView = layoutEvent.rootView
                                    sb.appendLine("""        "rootView": """)
                                    snapshotViewNodeToJson(sb, rootView, stringTable, "        ")
                                }
                                if (layoutEvent.hasScreenshot()) {
                                    sb.appendLine(""",""")
                                    sb.appendLine("""        "screenshot": {""")
                                    sb.appendLine("""          "type": "${layoutEvent.screenshot.type}",""")
                                    sb.appendLine("""          "bytesSize": ${layoutEvent.screenshot.bytes.size()}""")
                                    sb.appendLine("""        }""")
                                }
                            }
                            sb.appendLine()
                            sb.append("""      }""")
                            if (snapshotIndex < viewSnapshot.windowSnapshotsCount - 1) sb.append(",")
                            sb.appendLine()
                        }
                        sb.appendLine("""    ]""")
                    }

                    sb.appendLine("""  },""")
                }

                // Compose info
                if (snapshot.composeInfoCount > 0) {
                    sb.appendLine("""  "composeInfo": [""")
                    snapshot.composeInfoList.forEachIndexed { infoIndex, composeInfo ->
                        sb.appendLine("""    {""")
                        sb.appendLine("""      "viewId": ${composeInfo.viewId},""")
                        if (composeInfo.hasComposables()) {
                            val composables = composeInfo.composables
                            // Build string table map for compose
                            val composeStringTable = composables.stringsList.associate { it.id to it.str }
                            sb.appendLine("""      "composables": {""")
                            sb.appendLine("""        "rootsCount": ${composables.rootsCount},""")
                            if (composables.rootsCount > 0) {
                                sb.appendLine("""        "roots": [""")
                                composables.rootsList.forEachIndexed { rootIndex, root ->
                                    composeRootFromSnapshotToJson(sb, root, composeStringTable, "          ")
                                    if (rootIndex < composables.rootsCount - 1) sb.appendLine(",")
                                    else sb.appendLine()
                                }
                                sb.appendLine("""        ]""")
                            }
                            sb.appendLine("""      }""")
                        }
                        sb.append("""    }""")
                        if (infoIndex < snapshot.composeInfoCount - 1) sb.append(",")
                        sb.appendLine()
                    }
                    sb.appendLine("""  ]""")
                } else {
                    sb.appendLine("""  "composeInfo": []""")
                }

                sb.appendLine("}")

                // Write JSON output
                val outputPath = outputFile ?: File(inputFile.nameWithoutExtension + ".json")
                outputPath.writeText(sb.toString())
                println("Converted to: ${outputPath.absolutePath}")

                // Print summary
                if (snapshot.hasViewSnapshot()) {
                    val vs = snapshot.viewSnapshot
                    var nodeCount = 0
                    vs.windowSnapshotsList.forEach { ws ->
                        if (ws.hasLayout() && ws.layout.hasRootView()) {
                            nodeCount += countSnapshotViewNode(ws.layout.rootView)
                        }
                    }
                    println("  View nodes: $nodeCount")
                }
                if (snapshot.composeInfoCount > 0) {
                    var composeNodes = 0
                    snapshot.composeInfoList.forEach { ci ->
                        if (ci.hasComposables()) {
                            ci.composables.rootsList.forEach { root ->
                                composeNodes += countComposeNodes(root)
                            }
                        }
                    }
                    println("  Compose nodes: $composeNodes")
                }

                return 0
            }
        } catch (e: Exception) {
            System.err.println("Error converting file: ${e.message}")
            if (verbose) e.printStackTrace()
            return 1
        }
    }

    private fun getStringFromTable(stringTable: Map<Int, String>, index: Int): String {
        return stringTable[index] ?: ""
    }

    private fun snapshotViewNodeToJson(
        sb: StringBuilder,
        node: com.android.tools.idea.layoutinspector.view.inspection.ViewNode,
        stringTable: Map<Int, String>,
        indent: String
    ) {
        sb.appendLine("$indent{")

        // ViewNode has node (ViewNodeData) and children
        if (node.hasNode()) {
            val nodeData = node.node
            sb.appendLine("""$indent  "id": ${nodeData.id},""")
            sb.appendLine("""$indent  "className": "${escapeJson(nodeData.className)}",""")
            sb.appendLine("""$indent  "packageName": "${escapeJson(nodeData.packageName)}",""")

            // Bounds
            if (nodeData.hasBounds()) {
                val b = nodeData.bounds
                sb.appendLine("""$indent  "bounds": {"x": ${b.x}, "y": ${b.y}, "w": ${b.w}, "h": ${b.h}},""")
            }

            // Resource info
            if (nodeData.hasResource()) {
                val res = nodeData.resource
                sb.appendLine("""$indent  "resource": {""")
                sb.appendLine("""$indent    "type": ${res.type},""")
                sb.appendLine("""$indent    "namespace": ${res.namespace},""")
                sb.appendLine("""$indent    "name": "${escapeJson(res.name)}" """)
                sb.appendLine("""$indent  },""")
            }
        }

        // Children
        if (node.childrenCount > 0) {
            sb.appendLine("""$indent  "children": [""")
            node.childrenList.forEachIndexed { childIndex, child ->
                snapshotViewNodeToJson(sb, child, stringTable, "$indent    ")
                if (childIndex < node.childrenCount - 1) sb.appendLine(",")
                else sb.appendLine()
            }
            sb.appendLine("""$indent  ]""")
        } else {
            sb.appendLine("""$indent  "children": []""")
        }
        sb.append("$indent}")
    }

    private fun composeRootFromSnapshotToJson(
        sb: StringBuilder,
        root: layoutinspector.compose.inspection.ComposableRoot,
        stringTable: Map<Int, String>,
        indent: String
    ) {
        sb.appendLine("$indent{")
        sb.appendLine("""$indent  "viewId": ${root.viewId},""")
        if (root.nodesCount > 0) {
            sb.appendLine("""$indent  "nodes": [""")
            root.nodesList.forEachIndexed { nodeIndex, node ->
                composeNodeFromSnapshotToJson(sb, node, stringTable, "$indent    ")
                if (nodeIndex < root.nodesCount - 1) sb.appendLine(",")
                else sb.appendLine()
            }
            sb.appendLine("""$indent  ]""")
        } else {
            sb.appendLine("""$indent  "nodes": []""")
        }
        sb.append("$indent}")
    }

    private fun composeNodeFromSnapshotToJson(
        sb: StringBuilder,
        node: layoutinspector.compose.inspection.ComposableNode,
        stringTable: Map<Int, String>,
        indent: String
    ) {
        fun getString(id: Int): String = stringTable[id] ?: ""

        sb.appendLine("$indent{")
        sb.appendLine("""$indent  "id": ${node.id},""")
        sb.appendLine("""$indent  "name": "${escapeJson(getString(node.name))}",""")
        sb.appendLine("""$indent  "filename": "${escapeJson(getString(node.filename))}",""")
        sb.appendLine("""$indent  "lineNumber": ${node.lineNumber},""")
        sb.appendLine("""$indent  "packageHash": ${node.packageHash},""")

        val b = node.bounds
        val boundsStr = if (b.hasLayout()) {
            val l = b.layout
            """{"x": ${l.x}, "y": ${l.y}, "w": ${l.w}, "h": ${l.h}}"""
        } else if (b.hasRender()) {
            val r = b.render
            """{"x0": ${r.x0}, "y0": ${r.y0}, "x1": ${r.x1}, "y1": ${r.y1}, "x2": ${r.x2}, "y2": ${r.y2}, "x3": ${r.x3}, "y3": ${r.y3}}"""
        } else {
            """{}"""
        }
        sb.appendLine("""$indent  "bounds": $boundsStr,""")

        if (node.childrenCount > 0) {
            sb.appendLine("""$indent  "children": [""")
            node.childrenList.forEachIndexed { childIndex, child ->
                composeNodeFromSnapshotToJson(sb, child, stringTable, "$indent    ")
                if (childIndex < node.childrenCount - 1) sb.appendLine(",")
                else sb.appendLine()
            }
            sb.appendLine("""$indent  ]""")
        } else {
            sb.appendLine("""$indent  "children": []""")
        }
        sb.append("$indent}")
    }

    private fun countSnapshotViewNode(node: com.android.tools.idea.layoutinspector.view.inspection.ViewNode): Int {
        return 1 + node.childrenList.sumOf { countSnapshotViewNode(it) }
    }

    private fun countComposeNodes(root: layoutinspector.compose.inspection.ComposableRoot): Int {
        return root.nodesList.sumOf { countComposeNode(it) }
    }

    private fun countComposeNode(node: layoutinspector.compose.inspection.ComposableNode): Int {
        return 1 + node.childrenList.sumOf { countComposeNode(it) }
    }

    /**
     * Raw JSON extraction for files with incompatible proto format.
     * This extracts string tables and view structure using manual wire parsing.
     */
    private fun convertLiToJsonRaw(
        versionJson: String,
        metadata: Metadata,
        snapshotData: ByteArray,
        outputFile: File
    ): Int {
        if (verbose) println("  Using raw proto extraction...")

        // Extract ALL string tables from the snapshot
        val allStringTables = extractAllStringTables(snapshotData)
        if (verbose) println("  Found ${allStringTables.size} string table sections")

        // Categorize strings by type
        val viewClasses = mutableListOf<String>()
        val composeComposables = mutableListOf<String>()
        val composeSourceFiles = mutableListOf<String>()
        val parameterNames = mutableListOf<String>()
        val textValues = mutableListOf<String>()
        val otherStrings = mutableListOf<String>()

        for ((_, str) in allStringTables) {
            when {
                str.endsWith("View") || str.endsWith("Layout") || str.contains(".view.") || str.contains(".widget.") ->
                    viewClasses.add(str)
                str.endsWith(".kt") || str.endsWith(".java") ->
                    composeSourceFiles.add(str)
                str.matches("^[A-Z][a-zA-Z0-9]*$".toRegex()) && str.length > 2 ->
                    composeComposables.add(str)
                str.matches("^[a-z][a-zA-Z0-9]*$".toRegex()) ->
                    parameterNames.add(str)
                str.contains(" ") && str.length > 3 && str.all { it.code in 32..126 } ->
                    textValues.add(str)
                str.length > 2 ->
                    otherStrings.add(str)
            }
        }

        // Also extract any raw text values not in string tables
        val rawTextValues = extractRawTextValues(snapshotData)
        textValues.addAll(rawTextValues)

        if (verbose) {
            println("    View classes: ${viewClasses.size}")
            println("    Composables: ${composeComposables.size}")
            println("    Source files: ${composeSourceFiles.size}")
            println("    Parameters: ${parameterNames.size}")
            println("    Text values: ${textValues.distinct().size}")
        }

        // Try to extract compose tree hierarchy
        val composeTree = extractComposeHierarchy(snapshotData, allStringTables)

        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "version": ${versionJson.replace("{", "").replace("}", "")},""")
        sb.appendLine("""  "metadata": {""")
        sb.appendLine("""    "apiLevel": ${metadata.apiLevel},""")
        sb.appendLine("""    "processName": "${escapeJson(metadata.processName)}",""")
        sb.appendLine("""    "containsCompose": ${metadata.containsCompose},""")
        sb.appendLine("""    "source": "${metadata.source}",""")
        sb.appendLine("""    "sourceVersion": "${escapeJson(metadata.sourceVersion)}",""")
        sb.appendLine("""    "dpi": ${metadata.dpi},""")
        sb.appendLine("""    "fontScale": ${metadata.fontScale},""")
        sb.appendLine("""    "screenWidth": ${metadata.screenWidth},""")
        sb.appendLine("""    "screenHeight": ${metadata.screenHeight}""")
        sb.appendLine("""  },""")

        // Compose tree hierarchy
        if (composeTree.isNotEmpty()) {
            sb.appendLine("""  "composeHierarchy": [""")
            composeTree.forEachIndexed { index, node ->
                sb.append(renderComposeNodeJson(node, "    ", index == composeTree.size - 1))
            }
            sb.appendLine("""  ],""")
        }

        // View hierarchy (flat list for now)
        sb.appendLine("""  "viewClasses": [""")
        viewClasses.distinct().sorted().forEachIndexed { index, vc ->
            sb.append("""    "${escapeJson(vc)}"""")
            if (index < viewClasses.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Compose composables (flat list)
        sb.appendLine("""  "composeComposables": [""")
        composeComposables.distinct().sorted().forEachIndexed { index, comp ->
            sb.append("""    "${escapeJson(comp)}"""")
            if (index < composeComposables.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Source files
        sb.appendLine("""  "sourceFiles": [""")
        composeSourceFiles.distinct().sorted().forEachIndexed { index, file ->
            sb.append("""    "${escapeJson(file)}"""")
            if (index < composeSourceFiles.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Parameter names
        sb.appendLine("""  "parameterNames": [""")
        parameterNames.distinct().sorted().forEachIndexed { index, param ->
            sb.append("""    "${escapeJson(param)}"""")
            if (index < parameterNames.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Text values (user-visible strings)
        sb.appendLine("""  "textContent": [""")
        textValues.distinct().sorted().forEachIndexed { index, text ->
            sb.append("""    "${escapeJson(text)}"""")
            if (index < textValues.distinct().size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  ],""")

        // Full string table for reference
        sb.appendLine("""  "allStrings": {""")
        allStringTables.entries.sortedBy { it.key }.forEachIndexed { index, (id, str) ->
            sb.append("""    "$id": "${escapeJson(str)}"""")
            if (index < allStringTables.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("""  }""")

        sb.appendLine("}")

        outputFile.writeText(sb.toString())
        println("Converted to: ${outputFile.absolutePath}")
        println("  Compose tree nodes: ${countNodes(composeTree)}")
        println("  View classes: ${viewClasses.distinct().size}")
        println("  Composables: ${composeComposables.distinct().size}")
        println("  Source files: ${composeSourceFiles.distinct().size}")
        println("  Text content: ${textValues.distinct().size}")

        return 0
    }

    // Data class for parsed compose node
    data class ParsedComposableNode(
        val id: Long,
        val name: String,
        val filename: String,
        val lineNumber: Int,
        val bounds: QuadBounds?,
        val children: MutableList<ParsedComposableNode> = mutableListOf(),
        val flags: Int = 0,
        val recomposeCount: Int = 0,
        val skipCount: Int = 0,
        val properties: MutableMap<String, Any> = mutableMapOf()
    )

    data class QuadBounds(
        val x0: Int, val y0: Int,
        val x1: Int, val y1: Int,
        val x2: Int, val y2: Int,
        val x3: Int, val y3: Int
    ) {
        val x: Int get() = minOf(x0, x1, x2, x3)
        val y: Int get() = minOf(y0, y1, y2, y3)
        val right: Int get() = maxOf(x0, x1, x2, x3)
        val bottom: Int get() = maxOf(y0, y1, y2, y3)
        val width: Int get() = right - x
        val height: Int get() = bottom - y
    }

    private fun countNodes(nodes: List<ParsedComposableNode>): Int {
        return nodes.sumOf { 1 + countNodes(it.children) }
    }

    private fun renderComposeNodeJson(node: ParsedComposableNode, indent: String, isLast: Boolean): String {
        val sb = StringBuilder()
        sb.append("""$indent{""")
        sb.appendLine()
        sb.appendLine("""$indent  "name": "${escapeJson(node.name)}",""")
        if (node.filename.isNotEmpty()) {
            sb.appendLine("""$indent  "file": "${escapeJson(node.filename)}",""")
        }
        if (node.lineNumber > 0) {
            sb.appendLine("""$indent  "line": ${node.lineNumber},""")
        }
        if (node.bounds != null) {
            sb.appendLine("""$indent  "bounds": {"x": ${node.bounds.x}, "y": ${node.bounds.y}, "width": ${node.bounds.width}, "height": ${node.bounds.height}},""")
        }
        // Output properties (text, content, etc.)
        if (node.properties.isNotEmpty()) {
            node.properties.forEach { (key, value) ->
                when (value) {
                    is String -> sb.appendLine("""$indent  "$key": "${escapeJson(value)}",""")
                    is Number -> sb.appendLine("""$indent  "$key": $value,""")
                    is Boolean -> sb.appendLine("""$indent  "$key": $value,""")
                    else -> sb.appendLine("""$indent  "$key": "${escapeJson(value.toString())}",""")
                }
            }
        }
        if (node.children.isNotEmpty()) {
            sb.appendLine("""$indent  "children": [""")
            node.children.forEachIndexed { index, child ->
                sb.append(renderComposeNodeJson(child, "$indent    ", index == node.children.size - 1))
            }
            sb.appendLine("""$indent  ],""")
        }
        sb.appendLine("""$indent  "id": ${node.id}""")
        sb.append("""$indent}""")
        if (!isLast) sb.append(",")
        sb.appendLine()
        return sb.toString()
    }

    /**
     * Extract compose hierarchy from actual Compose inspector response data.
     * This uses the properly parsed protobuf data from the Compose inspector.
     */
    private fun extractComposeFromInspectorData(composeData: ComposeData): Pair<List<ParsedComposableNode>, Map<Int, String>> {
        val composablesResponse = composeData.composablesResponse
            ?: return Pair(emptyList(), emptyMap())

        // Build string table from the response
        val stringTable = mutableMapOf<Int, String>()
        for (stringEntry in composablesResponse.stringsList) {
            stringTable[stringEntry.id] = stringEntry.str
        }

        if (verbose) {
            println("    Compose string table:")
            stringTable.entries.sortedBy { it.key }.take(25).forEach { (id, str) ->
                println("      $id -> $str")
            }
        }

        // Build parameter properties map from parameters response
        val nodePropertiesMap = buildNodePropertiesMap(composeData.parametersResponse, stringTable)
        if (verbose && nodePropertiesMap.isNotEmpty()) {
            println("    Built properties map for ${nodePropertiesMap.size} composables")
        }

        // Extract the tree structure from roots
        val nodes = mutableListOf<ParsedComposableNode>()

        if (verbose) println("    Processing ${composablesResponse.rootsCount} roots...")

        for ((index, root) in composablesResponse.rootsList.withIndex()) {
            if (verbose) println("    Root $index: viewId=${root.viewId} nodesCount=${root.nodesCount}")
            for (node in root.nodesList) {
                if (verbose) println("      Node: id=${node.id} name=${node.name} (${stringTable[node.name]}) children=${node.childrenCount}")

                // If the node has no name (name=0 means no string), extract its children directly
                val nodeName = stringTable[node.name]
                if (nodeName == null && node.childrenCount > 0) {
                    if (verbose) println("      Wrapper node - extracting ${node.childrenCount} children")
                    for (child in node.childrenList) {
                        val parsedChild = parseComposableNodeFromProto(child, stringTable, nodePropertiesMap)
                        if (parsedChild != null) {
                            nodes.add(parsedChild)
                        }
                    }
                } else {
                    val parsedNode = parseComposableNodeFromProto(node, stringTable, nodePropertiesMap)
                    if (parsedNode != null) {
                        nodes.add(parsedNode)
                    }
                }
            }
        }

        if (verbose) println("    Extracted ${nodes.size} top-level nodes")
        return Pair(nodes, stringTable)
    }

    /**
     * Build a map from composable ID to its properties extracted from parameters.
     */
    private fun buildNodePropertiesMap(
        parametersResponse: layoutinspector.compose.inspection.GetAllParametersResponse?,
        composablesStringTable: Map<Int, String>
    ): Map<Long, Map<String, Any>> {
        if (parametersResponse == null) return emptyMap()

        // Build string table from parameters response
        val paramsStringTable = mutableMapOf<Int, String>()
        for (stringEntry in parametersResponse.stringsList) {
            paramsStringTable[stringEntry.id] = stringEntry.str
        }
        // Merge with composables string table
        paramsStringTable.putAll(composablesStringTable)

        val result = mutableMapOf<Long, MutableMap<String, Any>>()

        for (paramGroup in parametersResponse.parameterGroupsList) {
            val composableId = paramGroup.composableId
            val properties = mutableMapOf<String, Any>()

            for (param in paramGroup.parameterList) {
                extractPropertyFromParameter(param, paramsStringTable, properties)
            }

            if (properties.isNotEmpty()) {
                result[composableId] = properties
            }
        }

        return result
    }

    /**
     * Extract relevant properties from a parameter into the properties map.
     */
    private fun extractPropertyFromParameter(
        param: layoutinspector.compose.inspection.Parameter,
        stringTable: Map<Int, String>,
        properties: MutableMap<String, Any>
    ) {
        val paramName = stringTable[param.name] ?: return

        // Property names we want to include
        val relevantProps = setOf(
            "text", "content", "label", "title", "message", "hint", "value",
            "placeholder", "contentDescription", "onClick", "enabled", "checked",
            "selected", "visible", "alpha", "color", "backgroundColor", "textColor"
        )

        val lowerParamName = paramName.lowercase()
        if (lowerParamName !in relevantProps && !lowerParamName.endsWith("text") && !lowerParamName.endsWith("label")) {
            return
        }

        // Extract value based on type
        when (param.type) {
            layoutinspector.compose.inspection.Parameter.Type.STRING -> {
                val strValue = stringTable[param.int32Value]
                if (strValue != null && strValue.isNotEmpty() && isUserVisibleTextValue(strValue, paramName)) {
                    properties[paramName] = strValue
                }
            }
            layoutinspector.compose.inspection.Parameter.Type.BOOLEAN -> {
                properties[paramName] = param.int32Value != 0
            }
            layoutinspector.compose.inspection.Parameter.Type.INT32 -> {
                properties[paramName] = param.int32Value
            }
            layoutinspector.compose.inspection.Parameter.Type.INT64 -> {
                properties[paramName] = param.int64Value
            }
            layoutinspector.compose.inspection.Parameter.Type.FLOAT -> {
                properties[paramName] = param.floatValue
            }
            layoutinspector.compose.inspection.Parameter.Type.DOUBLE -> {
                properties[paramName] = param.doubleValue
            }
            layoutinspector.compose.inspection.Parameter.Type.COLOR -> {
                // Convert color int to hex string
                val colorInt = param.int32Value
                properties[paramName] = String.format("#%08X", colorInt)
            }
            else -> {
                // For complex types, check nested elements
                for (element in param.elementsList) {
                    extractPropertyFromParameter(element, stringTable, properties)
                }
            }
        }
    }

    /**
     * Parse a ComposableNode from the proto message into our ParsedComposableNode format.
     */
    private fun parseComposableNodeFromProto(
        node: layoutinspector.compose.inspection.ComposableNode,
        stringTable: Map<Int, String>,
        nodePropertiesMap: Map<Long, Map<String, Any>> = emptyMap()
    ): ParsedComposableNode? {
        val name = stringTable[node.name] ?: return null
        val filename = stringTable.getOrDefault(node.filename, "")

        // Parse bounds if present
        val bounds = if (node.hasBounds()) {
            val b = node.bounds
            // Check if using Rect layout format (width/height) or Quad render format (x0,y0,x1,y1...)
            if (b.hasLayout()) {
                val s = b.layout
                QuadBounds(s.x, s.y, s.x + s.w, s.y, s.x + s.w, s.y + s.h, s.x, s.y + s.h)
            } else if (b.hasRender()) {
                val r = b.render
                QuadBounds(r.x0, r.y0, r.x1, r.y1, r.x2, r.y2, r.x3, r.y3)
            } else {
                null
            }
        } else null

        // Recursively parse children
        val children = mutableListOf<ParsedComposableNode>()
        for (childNode in node.childrenList) {
            val parsedChild = parseComposableNodeFromProto(childNode, stringTable, nodePropertiesMap)
            if (parsedChild != null) {
                children.add(parsedChild)
            }
        }

        // Get properties for this node from the map
        val properties = nodePropertiesMap[node.id]?.toMutableMap() ?: mutableMapOf()

        return ParsedComposableNode(
            id = node.id,
            name = name,
            filename = filename,
            lineNumber = node.lineNumber,
            bounds = bounds,
            children = children,
            flags = node.flags,
            recomposeCount = node.recomposeCount,
            skipCount = node.recomposeSkips,
            properties = properties
        )
    }

    /**
     * Extract text values from GetAllParametersResponse.
     * Looks for parameters named "text" or containing string values that appear to be user-visible text.
     */
    private fun extractTextFromParameters(
        parametersResponse: layoutinspector.compose.inspection.GetAllParametersResponse
    ): List<String> {
        val textValues = mutableListOf<String>()

        // Build string table from parameters response
        val stringTable = mutableMapOf<Int, String>()
        for (stringEntry in parametersResponse.stringsList) {
            stringTable[stringEntry.id] = stringEntry.str
        }

        if (verbose) {
            println("    Parameters string table (${stringTable.size} entries):")
            stringTable.entries.sortedBy { it.key }.take(10).forEach { (id, str) ->
                println("      $id -> $str")
            }
        }

        // Iterate through all parameter groups (each corresponds to a composable)
        if (verbose) {
            println("    Processing ${parametersResponse.parameterGroupsCount} parameter groups...")
        }
        var totalParams = 0
        for (paramGroup in parametersResponse.parameterGroupsList) {
            if (verbose && paramGroup.parameterCount > 0) {
                println("      Composable ${paramGroup.composableId}: ${paramGroup.parameterCount} params")
            }
            for (param in paramGroup.parameterList) {
                totalParams++
                extractTextFromParameter(param, stringTable, textValues, verbose)
            }
        }
        if (verbose) {
            println("    Total parameters examined: $totalParams, text values found: ${textValues.size}")
        }

        return textValues.distinct()
    }

    /**
     * Recursively extract text values from a parameter and its nested elements.
     */
    private fun extractTextFromParameter(
        param: layoutinspector.compose.inspection.Parameter,
        stringTable: Map<Int, String>,
        textValues: MutableList<String>,
        verbose: Boolean = false
    ) {
        // Field 2 (name) is the parameter name string ID
        val paramName = stringTable[param.name] ?: ""

        // For STRING type parameters, int32_value (field 11) is a string table reference
        val isStringType = param.type == layoutinspector.compose.inspection.Parameter.Type.STRING
        val valueStr = if (isStringType && param.int32Value != 0) {
            stringTable[param.int32Value]
        } else null

        // Debug output for all parameter values
        if (verbose && paramName.isNotEmpty()) {
            val valueType = when {
                valueStr != null -> "ref -> '$valueStr'"
                param.int32Value != 0 -> "int32: ${param.int32Value}"
                param.int64Value != 0L -> "int64: ${param.int64Value}"
                param.doubleValue != 0.0 -> "double: ${param.doubleValue}"
                param.hasReference() -> "reference"
                param.hasLambdaValue() -> "lambda"
                else -> "type: ${param.type}"
            }
            println("        param '$paramName' -> $valueType")
        }

        // Check if this is a string value (by reference in int32_value for STRING type)
        if (valueStr != null) {
            // Add if it looks like user-visible text (not a class name, enum value, etc.)
            if (isUserVisibleTextValue(valueStr, paramName)) {
                textValues.add(valueStr)
                if (verbose) println("    Found text: '$valueStr' (param: $paramName)")
            }
        }

        // Also check nested elements (for composite parameters like AnnotatedString)
        param.elementsList.forEach { element ->
            extractTextFromParameter(element, stringTable, textValues, verbose)
        }
    }

    /**
     * Check if a string value appears to be user-visible text content.
     */
    private fun isUserVisibleTextValue(value: String, paramName: String): Boolean {
        // Empty or very short strings are usually not user text
        if (value.length < 2) return false

        // Parameter name hints: "text", "content", "label", "title", "message", "hint"
        val textParamNames = setOf("text", "content", "label", "title", "message", "hint", "value", "placeholder")
        if (paramName.lowercase() in textParamNames) return true

        // Skip common non-text patterns
        // Package names (contains dots and mostly lowercase)
        if (value.contains(".") && value.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return false

        // Class names (PascalCase without spaces)
        if (value.matches("^[A-Z][a-zA-Z0-9]+$".toRegex())) return false

        // Enum values (UPPER_SNAKE_CASE)
        if (value.matches("^[A-Z][A-Z0-9_]*$".toRegex())) return false

        // Color/resource values
        if (value.startsWith("#") || value.startsWith("@")) return false

        // If it has mixed case with spaces or is a simple phrase, it's likely text
        if (value.contains(" ")) return true

        // Short alphanumeric strings could be labels/buttons
        if (value.length <= 20 && value.any { it.isLetter() }) return true

        return false
    }

    /**
     * Extract the compose hierarchy from snapshot data.
     * Parses the protobuf wire format to find ComposableNode structures.
     *
     * The actual format from Android device uses:
     * - ComposableNode.id (field 1, varint)
     * - ComposableNode.name (field 2, varint) - NOTE: in actual device format, field 2 can be children!
     * - ComposableNode.filename (field 3, varint)
     * - ComposableNode.children (field 9, embedded message)
     */
    private fun extractComposeHierarchy(data: ByteArray, stringTable: Map<Int, String>): List<ParsedComposableNode> {
        if (verbose) println("  Extracting compose hierarchy...")

        // Build a simple tree by finding all nodes that reference composable names
        // The string table maps: 2 -> SnapshotExampleTheme, 4 -> MaterialTheme, etc.
        val reverseStringTable = stringTable.entries.associate { it.value to it.key }

        // Look for sequences that reference string table indices
        // Pattern: name_field (0x10 XX) where XX maps to a known composable
        val nodeData = mutableListOf<Triple<Int, Long, Int>>() // (position, possible_id, name_index)

        var pos = 0
        while (pos < data.size - 6) {
            val b = data[pos].toInt() and 0xFF

            // Look for field 2 (name) = 0x10 followed by a valid string table index
            if (b == 0x10 && pos > 2) {
                val (nameIdx, nameBytes) = readVarintAt(data, pos + 1)
                if (nameIdx in stringTable.keys) {
                    val name = stringTable[nameIdx]
                    // Check if this looks like a composable name
                    if (name != null && name.first().isUpperCase() && !name.endsWith(".kt")) {
                        // Look back for field 1 (id) = 0x08
                        var searchBack = pos - 1
                        while (searchBack > 0 && searchBack > pos - 20) {
                            if ((data[searchBack].toInt() and 0xFF) == 0x08) {
                                val (id, _) = readVarint64At(data, searchBack + 1)
                                nodeData.add(Triple(searchBack, id, nameIdx))
                                break
                            }
                            searchBack--
                        }
                    }
                }
            }
            pos++
        }

        if (verbose) println("    Found ${nodeData.size} potential nodes by name lookup")

        // Convert to parsed nodes
        val allNodes = mutableListOf<ParsedComposableNode>()
        for ((nodePos, id, nameIdx) in nodeData) {
            val name = stringTable[nameIdx] ?: continue
            // Parse forward from nodePos to get more details
            val node = tryParseComposableNodeFlexible(data, nodePos, stringTable)
            if (node != null) {
                allNodes.add(node)
            } else {
                // Create minimal node
                allNodes.add(ParsedComposableNode(
                    id = id,
                    name = name,
                    filename = "",
                    lineNumber = 0,
                    bounds = null
                ))
            }
        }

        // Deduplicate by name, keeping the node with most children/info
        // Since we're scanning binary data, we'll get false positives - only keep the best candidate for each composable
        val nodeByName = mutableMapOf<String, ParsedComposableNode>()
        for (node in allNodes) {
            val existing = nodeByName[node.name]
            if (existing == null ||
                node.children.size > existing.children.size ||
                (node.filename.isNotEmpty() && existing.filename.isEmpty()) ||
                (node.lineNumber > 0 && existing.lineNumber == 0 && node.lineNumber < 1000) ||
                (node.bounds != null && existing.bounds == null)) {
                // Prefer nodes with reasonable line numbers (filter out garbage)
                if (node.lineNumber == 0 || node.lineNumber < 1000) {
                    nodeByName[node.name] = node
                }
            }
        }

        if (verbose) println("    Deduplicated to ${nodeByName.size} unique composables")

        // Find root nodes
        val childIds = mutableSetOf<Long>()
        for (node in nodeByName.values) {
            collectChildIds(node, childIds)
        }

        val rootNodes = nodeByName.values.filter { it.id !in childIds }
            .sortedByDescending { countNodesRecursive(it) }

        // Return nodes sorted by string table order (approximate compose hierarchy)
        // Filter to known composable names (from string table)
        val composableNames = stringTable.values.filter { it.first().isUpperCase() && !it.endsWith(".kt") }.toSet()
        val validNodes = nodeByName.values.filter { it.name in composableNames }
            .sortedBy { reverseStringTable[it.name] ?: Int.MAX_VALUE }

        return if (rootNodes.any { it.children.isNotEmpty() }) {
            rootNodes
        } else {
            validNodes
        }
    }

    /**
     * Flexible parsing that handles variations in the proto format.
     */
    private fun tryParseComposableNodeFlexible(
        data: ByteArray,
        startPos: Int,
        stringTable: Map<Int, String>
    ): ParsedComposableNode? {
        try {
            var pos = startPos
            var id: Long = 0
            var nameIdx = 0
            var filenameIdx = 0
            var lineNumber = 0
            var bounds: QuadBounds? = null
            val children = mutableListOf<ParsedComposableNode>()

            // Parse up to 30 fields or 200 bytes, whichever comes first
            val endPos = minOf(startPos + 200, data.size)
            var fieldCount = 0

            while (pos < endPos && fieldCount < 30) {
                val tag = data[pos].toInt() and 0xFF
                val fieldNumber = tag shr 3
                val wireType = tag and 0x07

                when {
                    // Field 1: id (varint)
                    fieldNumber == 1 && wireType == 0 -> {
                        pos++
                        val (value, bytes) = readVarint64At(data, pos)
                        id = value
                        pos += bytes
                    }
                    // Field 2: name (varint) - string table index
                    fieldNumber == 2 && wireType == 0 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        nameIdx = value
                        pos += bytes
                    }
                    // Field 2: could also be children (length-delimited) in some formats
                    fieldNumber == 2 && wireType == 2 -> {
                        pos++
                        val (len, bytes) = readVarintAt(data, pos)
                        pos += bytes
                        // Try to parse as child node
                        if (len > 5 && pos + len <= endPos) {
                            val child = tryParseComposableNodeFlexible(data, pos, stringTable)
                            if (child != null && child.name.isNotEmpty()) {
                                children.add(child)
                            }
                        }
                        pos += len
                    }
                    // Field 3: filename (varint)
                    fieldNumber == 3 && wireType == 0 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        filenameIdx = value
                        pos += bytes
                    }
                    // Field 5: line_number (varint)
                    fieldNumber == 5 && wireType == 0 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        lineNumber = value
                        pos += bytes
                    }
                    // Field 8: bounds (length-delimited)
                    fieldNumber == 8 && wireType == 2 -> {
                        pos++
                        val (len, bytes) = readVarintAt(data, pos)
                        pos += bytes
                        if (len > 0 && pos + len <= endPos) {
                            bounds = tryParseBounds(data, pos, len)
                        }
                        pos += len
                    }
                    // Field 9: children (length-delimited)
                    fieldNumber == 9 && wireType == 2 -> {
                        pos++
                        val (len, bytes) = readVarintAt(data, pos)
                        pos += bytes
                        if (len > 5 && pos + len <= endPos) {
                            val child = tryParseComposableNodeFlexible(data, pos, stringTable)
                            if (child != null && child.name.isNotEmpty()) {
                                children.add(child)
                            }
                        }
                        pos += len
                    }
                    // Other varint field - skip
                    wireType == 0 -> {
                        pos++
                        val (_, bytes) = readVarintAt(data, pos)
                        pos += bytes
                    }
                    // Length-delimited field - skip
                    wireType == 2 -> {
                        pos++
                        val (len, bytes) = readVarintAt(data, pos)
                        pos += bytes + len
                    }
                    // Fixed 32/64 - skip
                    wireType == 1 -> { pos += 9 }
                    wireType == 5 -> { pos += 5 }
                    else -> break
                }
                fieldCount++
            }

            if (nameIdx == 0 && id == 0L) return null

            val name = stringTable[nameIdx] ?: ""
            val filename = stringTable[filenameIdx] ?: ""

            if (name.isEmpty() || !name.first().isUpperCase() || name.endsWith(".kt")) return null

            return ParsedComposableNode(
                id = id,
                name = name,
                filename = filename,
                lineNumber = lineNumber,
                bounds = bounds,
                children = children
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun collectChildIds(node: ParsedComposableNode, ids: MutableSet<Long>) {
        for (child in node.children) {
            ids.add(child.id)
            collectChildIds(child, ids)
        }
    }

    private fun countNodesRecursive(node: ParsedComposableNode): Int {
        return 1 + node.children.sumOf { countNodesRecursive(it) }
    }

    private fun containsNode(root: ParsedComposableNode, id: Long): Boolean {
        if (root.id == id) return true
        return root.children.any { containsNode(it, id) }
    }

    /**
     * Try to parse a ComposableNode at the given position.
     * Returns null if parsing fails or the data doesn't look like a valid node.
     */
    private fun tryParseComposableNode(
        data: ByteArray,
        startPos: Int,
        stringTable: Map<Int, String>,
        endPos: Int = data.size
    ): ParsedComposableNode? {
        try {
            var pos = startPos
            var id: Long = 0
            var nameIdx = 0
            var filenameIdx = 0
            var lineNumber = 0
            var bounds: QuadBounds? = null
            val children = mutableListOf<ParsedComposableNode>()
            var flags = 0
            var recomposeCount = 0
            var skipCount = 0

            // Parse fields until we hit end of message or invalid tag
            var fieldCount = 0
            while (pos < endPos && fieldCount < 50) {
                val tag = data[pos].toInt() and 0xFF
                val fieldNumber = tag shr 3
                val wireType = tag and 0x07

                when {
                    // Field 1: id (varint)
                    tag == 0x08 -> {
                        pos++
                        val (value, bytes) = readVarint64At(data, pos)
                        id = value
                        pos += bytes
                    }
                    // Field 2: name (varint - string table index)
                    tag == 0x10 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        nameIdx = value
                        pos += bytes
                    }
                    // Field 3: filename (varint - string table index)
                    tag == 0x18 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        filenameIdx = value
                        pos += bytes
                    }
                    // Field 4: package_hash (varint)
                    tag == 0x20 -> {
                        pos++
                        val (_, bytes) = readVarintAt(data, pos)
                        pos += bytes
                    }
                    // Field 5: line_number (varint)
                    tag == 0x28 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        lineNumber = value
                        pos += bytes
                    }
                    // Field 6: offset (varint)
                    tag == 0x30 -> {
                        pos++
                        val (_, bytes) = readVarintAt(data, pos)
                        pos += bytes
                    }
                    // Field 7: anchor_hash (varint)
                    tag == 0x38 -> {
                        pos++
                        val (_, bytes) = readVarintAt(data, pos)
                        pos += bytes
                    }
                    // Field 8: bounds (embedded message)
                    tag == 0x42 -> {
                        pos++
                        val (len, bytes) = readVarintAt(data, pos)
                        pos += bytes
                        if (len > 0 && pos + len <= endPos) {
                            bounds = tryParseBounds(data, pos, len)
                        }
                        pos += len
                    }
                    // Field 9: children (repeated embedded message)
                    tag == 0x4a -> {
                        pos++
                        val (len, bytes) = readVarintAt(data, pos)
                        pos += bytes
                        if (len > 0 && pos + len <= endPos) {
                            // Parse child within its length bounds
                            val childNode = tryParseComposableNode(data, pos, stringTable, pos + len)
                            if (childNode != null && childNode.name.isNotEmpty()) {
                                children.add(childNode)
                            }
                        }
                        pos += len
                    }
                    // Field 10: recompose_count (varint)
                    tag == 0x50 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        recomposeCount = value
                        pos += bytes
                    }
                    // Field 11: skip_count (varint)
                    tag == 0x58 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        skipCount = value
                        pos += bytes
                    }
                    // Field 12: flags (varint)
                    tag == 0x60 -> {
                        pos++
                        val (value, bytes) = readVarintAt(data, pos)
                        flags = value
                        pos += bytes
                    }
                    // Field 13: view_id (varint)
                    tag == 0x68 -> {
                        pos++
                        val (_, bytes) = readVarint64At(data, pos)
                        pos += bytes
                    }
                    // Unknown field or end of message
                    else -> {
                        // If wireType is 2 (length-delimited), skip it
                        if (wireType == 2 && fieldNumber > 0 && fieldNumber < 20) {
                            pos++
                            val (len, bytes) = readVarintAt(data, pos)
                            pos += bytes + len
                        } else {
                            break
                        }
                    }
                }
                fieldCount++
            }

            // Validate: must have at least id and name
            if (id == 0L && nameIdx == 0) return null

            val name = stringTable[nameIdx] ?: ""
            val filename = stringTable[filenameIdx] ?: ""

            // Filter out clearly invalid names
            if (name.isEmpty() || name.contains('\u0000') || name.any { it.code > 127 }) return null

            return ParsedComposableNode(
                id = id,
                name = name,
                filename = filename,
                lineNumber = lineNumber,
                bounds = bounds,
                children = children,
                flags = flags,
                recomposeCount = recomposeCount,
                skipCount = skipCount
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun tryParseBounds(data: ByteArray, startPos: Int, length: Int): QuadBounds? {
        try {
            var pos = startPos
            val endPos = startPos + length
            var x0 = 0; var y0 = 0; var x1 = 0; var y1 = 0
            var x2 = 0; var y2 = 0; var x3 = 0; var y3 = 0

            while (pos < endPos) {
                val tag = data[pos].toInt() and 0xFF
                pos++

                when (tag) {
                    0x08 -> { val (v, b) = readVarintAt(data, pos); x0 = decodeZigZag(v); pos += b }
                    0x10 -> { val (v, b) = readVarintAt(data, pos); y0 = decodeZigZag(v); pos += b }
                    0x18 -> { val (v, b) = readVarintAt(data, pos); x1 = decodeZigZag(v); pos += b }
                    0x20 -> { val (v, b) = readVarintAt(data, pos); y1 = decodeZigZag(v); pos += b }
                    0x28 -> { val (v, b) = readVarintAt(data, pos); x2 = decodeZigZag(v); pos += b }
                    0x30 -> { val (v, b) = readVarintAt(data, pos); y2 = decodeZigZag(v); pos += b }
                    0x38 -> { val (v, b) = readVarintAt(data, pos); x3 = decodeZigZag(v); pos += b }
                    0x40 -> { val (v, b) = readVarintAt(data, pos); y3 = decodeZigZag(v); pos += b }
                    else -> break
                }
            }

            return QuadBounds(x0, y0, x1, y1, x2, y2, x3, y3)
        } catch (e: Exception) {
            return null
        }
    }

    private fun decodeZigZag(n: Int): Int = (n ushr 1) xor -(n and 1)

    private fun readVarint64At(data: ByteArray, pos: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var bytesRead = 0
        var p = pos

        while (p < data.size && shift < 63) {
            val b = data[p].toLong() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            bytesRead++
            p++
            if ((b and 0x80) == 0L) break
            shift += 7
        }

        return Pair(value, bytesRead)
    }

    /**
     * Extract all string table entries from entire snapshot.
     * Scans for StringEntry pattern: 0a XX 08 YY 12 ZZ [string]
     */
    private fun extractAllStringTables(data: ByteArray): Map<Int, String> {
        val table = mutableMapOf<Int, String>()
        var pos = 0

        while (pos < data.size - 6) {
            val b0 = data[pos].toInt() and 0xFF

            // Look for StringEntry message start: 0a (field 1, wire type 2)
            if (b0 == 0x0a && pos + 3 < data.size) {
                val msgLen = readVarintAt(data, pos + 1)
                if (msgLen.second > 0 && msgLen.first in 4..250) {
                    val innerStart = pos + 1 + msgLen.second
                    if (innerStart < data.size && (data[innerStart].toInt() and 0xFF) == 0x08) {
                        // Found field 1 (id)
                        val idResult = readVarintAt(data, innerStart + 1)
                        val id = idResult.first
                        val afterId = innerStart + 1 + idResult.second

                        if (afterId < data.size && (data[afterId].toInt() and 0xFF) == 0x12) {
                            // Found field 2 (string)
                            val strLenResult = readVarintAt(data, afterId + 1)
                            val strLen = strLenResult.first
                            val strStart = afterId + 1 + strLenResult.second

                            if (strLen > 0 && strLen < 200 && strStart + strLen <= data.size) {
                                try {
                                    val str = String(data.copyOfRange(strStart, strStart + strLen), Charsets.UTF_8)
                                    if (str.all { it.code >= 32 || it == '\n' || it == '\r' || it == '\t' }) {
                                        table[id] = str
                                    }
                                } catch (e: Exception) { /* ignore */ }
                            }
                        }
                    }
                }
            }
            pos++
        }

        return table
    }

    /**
     * Read a varint from byte array at given position.
     * Returns (value, bytesRead).
     */
    private fun readVarintAt(data: ByteArray, pos: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var bytesRead = 0
        var p = pos

        while (p < data.size && shift < 35) {
            val b = data[p].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            bytesRead++
            p++
            if ((b and 0x80) == 0) break
            shift += 7
        }

        return Pair(value, bytesRead)
    }

    /**
     * Extract raw text values that look like user-visible content.
     * These may not be in the string tables.
     */
    private fun extractRawTextValues(data: ByteArray): List<String> {
        val texts = mutableSetOf<String>()

        // Look for patterns like "12 0e Hello Android!" (field 2, wire type 2, length, string)
        var pos = 0
        while (pos < data.size - 4) {
            val b0 = data[pos].toInt() and 0xFF
            if (b0 == 0x12 || b0 == 0x3a || b0 == 0x42) { // Various string fields (2, 7, 8)
                val lenResult = readVarintAt(data, pos + 1)
                val len = lenResult.first
                val strStart = pos + 1 + lenResult.second

                if (len in 4..100 && strStart + len <= data.size) {
                    try {
                        val str = String(data.copyOfRange(strStart, strStart + len), Charsets.UTF_8)
                        // Look for user-visible text (contains space, mostly letters)
                        if (str.contains(" ") &&
                            str.all { it.code in 32..126 } &&
                            str.count { it.isLetter() } > str.length * 0.5 &&
                            !str.contains("Italic") && !str.contains("Roboto") &&
                            !str.contains("intrinsic") && !str.contains("SubcomposeLayout")) {
                            texts.add(str)
                        }
                    } catch (e: Exception) { /* ignore */ }
                }
            }
            pos++
        }

        return texts.toList()
    }

    /**
     * Extract string table entries from protobuf data.
     * StringEntry format: field 1 (id) = varint, field 2 (str) = length-prefixed string
     */
    private fun extractStringTable(data: ByteArray): Map<Int, String> {
        val table = mutableMapOf<Int, String>()
        var pos = 0

        while (pos < data.size - 4) {
            // Look for pattern: 0a XX 08 YY 12 ZZ (StringEntry message start)
            val b0 = data[pos].toInt() and 0xFF

            if (b0 == 0x0a && pos + 3 < data.size) {
                val msgLen = data[pos + 1].toInt() and 0xFF
                if (msgLen > 3 && msgLen < 250 && pos + 2 + msgLen <= data.size) {
                    val b2 = data[pos + 2].toInt() and 0xFF
                    if (b2 == 0x08) { // field 1, wire type 0 (varint)
                        // Read id
                        var id = 0
                        var idPos = pos + 3
                        var shift = 0
                        while (idPos < data.size && shift < 35) {
                            val bv = data[idPos].toInt() and 0xFF
                            id = id or ((bv and 0x7F) shl shift)
                            idPos++
                            if ((bv and 0x80) == 0) break
                            shift += 7
                        }

                        // Look for field 2 (string)
                        if (idPos < data.size && (data[idPos].toInt() and 0xFF) == 0x12) {
                            idPos++
                            if (idPos < data.size) {
                                val strLen = data[idPos].toInt() and 0xFF
                                idPos++
                                if (strLen > 0 && idPos + strLen <= data.size) {
                                    try {
                                        val str = String(data.copyOfRange(idPos, idPos + strLen), Charsets.UTF_8)
                                        if (str.all { it.code >= 32 || it == '\n' || it == '\r' || it == '\t' }) {
                                            table[id] = str
                                        }
                                    } catch (e: Exception) { /* ignore */ }
                                }
                            }
                        }
                    }
                }
            }
            pos++
        }

        return table
    }

    /**
     * Extract view class names from string table
     */
    private fun extractViewClasses(stringTable: Map<Int, String>): List<String> {
        return stringTable.values
            .filter { it.endsWith("View") || it.endsWith("Layout") || it.contains(".view.") || it.contains(".widget.") }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    /**
     * Extract Compose component names from string table
     */
    private fun extractComposeComponents(stringTable: Map<Int, String>): List<String> {
        val composePatterns = listOf(
            "Scaffold", "Box", "Column", "Row", "Text", "Button", "Surface",
            "LazyColumn", "LazyRow", "Card", "Dialog", "TopAppBar", "BottomBar",
            "FloatingActionButton", "Icon", "Image", "Spacer", "Divider",
            "TextField", "Checkbox", "RadioButton", "Switch", "Slider",
            "BasicText", "Greeting", "MaterialTheme", "Theme"
        )

        return stringTable.values
            .filter { str ->
                composePatterns.any { pattern -> str.contains(pattern, ignoreCase = true) } ||
                str.contains("Compose") ||
                str.endsWith(".kt") ||
                (str.matches("^[A-Z][a-zA-Z0-9]*$".toRegex()) && str.length > 3) // PascalCase composables
            }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    /**
     * Extract text content (strings that look like user-visible text)
     */
    private fun extractTextValues(data: ByteArray): List<String> {
        val texts = mutableSetOf<String>()
        var pos = 0

        // Look for length-prefixed strings that look like text content
        while (pos < data.size - 2) {
            val len = data[pos].toInt() and 0xFF

            if (len in 3..100 && pos + 1 + len <= data.size) {
                try {
                    val str = String(data.copyOfRange(pos + 1, pos + 1 + len), Charsets.UTF_8)
                    // Look for text content patterns (contains spaces, or is a short meaningful phrase)
                    if (isUserVisibleText(str)) {
                        texts.add(str)
                    }
                } catch (e: Exception) { /* ignore */ }
            }
            pos++
        }

        return texts.toList().sortedBy { it.lowercase() }
    }

    private fun isUserVisibleText(str: String): Boolean {
        // Must have at least one space (phrase, not identifier)
        if (!str.contains(" ")) return false

        // All characters must be printable
        if (!str.all { it.code >= 32 }) return false

        // Not a class/package name
        if (str.contains(".") && str.all { it.isLetterOrDigit() || it == '.' }) return false

        // Not mostly non-letter characters (binary/font data)
        val letterCount = str.count { it.isLetter() }
        if (letterCount < str.length * 0.5) return false

        // Not too many special characters or digits
        val specialCount = str.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        if (specialCount > str.length * 0.3) return false

        // Skip font metadata patterns
        val fontPatterns = listOf("Italic", "Bold", "Regular", "Light", "Medium", "Condensed", "Roman", "Roboto")
        val fontMatchCount = fontPatterns.count { str.contains(it) }
        if (fontMatchCount >= 3) return false

        // Skip intrinsic measurement warning text
        if (str.contains("intrinsic") || str.contains("SubcomposeLayout")) return false

        return true
    }

    /**
     * Extract readable strings from raw protobuf bytes.
     * This scans for length-prefixed strings (wire type 2) in the protobuf data.
     */
    private fun extractStringsFromProtobuf(data: ByteArray): List<String> {
        val strings = mutableSetOf<String>()
        var pos = 0

        // Look for protobuf string fields - wire type 2 (length-delimited) with likely tag bytes
        while (pos < data.size - 2) {
            val byte1 = data[pos].toInt() and 0xFF

            // Check for wire type 2 (tag byte where lower 3 bits = 2)
            if ((byte1 and 0x07) == 2 && byte1 < 0x80) {
                pos++
                if (pos >= data.size) break

                // Read length (varint)
                var len = 0
                var shift = 0
                var byteVal: Int
                val startPos = pos
                do {
                    if (pos >= data.size) break
                    byteVal = data[pos].toInt() and 0xFF
                    len = len or ((byteVal and 0x7F) shl shift)
                    shift += 7
                    pos++
                } while ((byteVal and 0x80) != 0 && shift < 35)

                // Check for reasonable length
                if (len in 3..200 && pos + len <= data.size) {
                    val potentialString = data.copyOfRange(pos, pos + len)
                    // Check if it looks like UTF-8 text
                    if (isLikelyUtf8Text(potentialString)) {
                        val str = String(potentialString, Charsets.UTF_8)
                        // Filter for meaningful strings
                        if (isMeaningfulString(str)) {
                            strings.add(str)
                        }
                    }
                }
                // Don't skip ahead, continue scanning from next byte after tag
                pos = startPos
            }
            pos++
        }

        return strings.toList().sortedBy { it.lowercase() }
    }

    private fun isMeaningfulString(str: String): Boolean {
        if (str.isBlank() || str.length < 3) return false

        // Skip strings with too many control characters or binary data
        val controlCount = str.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
        if (controlCount > 0) return false

        // Keep strings that look like:
        // - Class names (android.view.View, com.example.MyClass)
        // - Resource IDs (R.id.something)
        // - Package names
        // - Compose function names
        val patterns = listOf(
            "^[A-Za-z][A-Za-z0-9_]*\$".toRegex(), // Simple identifiers
            "^[a-z]+\\.[a-z]".toRegex(), // Package names
            "View\$|Layout\$|Text\$|Button\$|Image".toRegex(), // UI components
            "^@[a-z]+/".toRegex(), // Resource references
            "Compose|Modifier|remember".toRegex(RegexOption.IGNORE_CASE) // Compose related
        )

        return patterns.any { it.containsMatchIn(str) } ||
            (str.contains(".") && str.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' })
    }

    private fun isLikelyUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false

        // Check if bytes form valid UTF-8
        try {
            val str = String(bytes, Charsets.UTF_8)
            // Check if it contains mostly printable characters
            val printableCount = str.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?-_()[]{}\"'`@#$%^&*+=/<>" }
            return printableCount >= str.length * 0.7
        } catch (e: Exception) {
            return false
        }
    }

    private fun listConnectedDevices(adbHelper: AdbHelper): Int {
        val devices = adbHelper.getDevices()
        if (devices.isEmpty()) {
            println("No devices connected")
            return 0
        }

        println("Connected devices:")
        devices.forEach { device ->
            val state = device.state?.name ?: "unknown"
            val model = device.getProperty("ro.product.model") ?: "unknown"
            val apiLevel = adbHelper.getApiLevel(device)
            println("  ${device.serialNumber}\t$state\t$model (API $apiLevel)")
        }
        return 0
    }

    private fun listDeviceProcesses(
        adbHelper: AdbHelper,
        device: com.android.ddmlib.IDevice
    ): Int {
        if (verbose) println("Listing processes on ${device.serialNumber}...")

        val processes = kotlinx.coroutines.runBlocking {
            adbHelper.listProcesses(device)
        }

        println("Running processes (${processes.size}):")
        processes.sortedBy { it.name }.forEach { process ->
            println("  ${process.pid}\t${process.name}")
        }
        return 0
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(LayoutInspectorCli()).execute(*args)
    exitProcess(exitCode)
}
