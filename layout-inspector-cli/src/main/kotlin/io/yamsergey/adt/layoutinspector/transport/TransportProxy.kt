package io.yamsergey.adt.layoutinspector.transport

import com.android.tools.profiler.proto.*
import com.android.tools.app.inspection.AppInspectionResponse
import com.android.tools.idea.layoutinspector.view.inspection.Event as ViewInspectorEvent
import com.android.tools.idea.layoutinspector.view.inspection.LayoutEvent
import com.android.tools.idea.layoutinspector.view.inspection.WindowRootsEvent
import io.grpc.*
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A minimal host-side transport proxy that:
 * 1. Connects to the device daemon via GetEvents streaming
 * 2. Receives events and stores them in memory
 * 3. Allows clients to poll for events via GetEventGroups
 * 4. Forwards Execute commands to the device daemon
 *
 * This is a simplified version of Android Studio's TransportServiceProxy.
 */
class TransportProxy(
    private val deviceStub: TransportServiceGrpc.TransportServiceBlockingStub,
    private val asyncStub: TransportServiceGrpc.TransportServiceStub,
    private val streamId: Long
) : TransportServiceGrpc.TransportServiceImplBase() {

    private val eventQueue = LinkedBlockingQueue<Event>()
    private val eventsByKindAndGroup = ConcurrentHashMap<Pair<Event.Kind, Long>, MutableList<Event>>()
    private val pendingResponses = ConcurrentHashMap<Int, CompletableDeferred<AppInspectionResponse>>()

    // Collected layout events from the view inspector
    private val layoutEvents = CopyOnWriteArrayList<LayoutEvent>()
    private val windowRootsEvents = CopyOnWriteArrayList<WindowRootsEvent>()
    private val rawInspectorEvents = CopyOnWriteArrayList<ByteArray>()

    // Raw layout event bytes (for when proto parsing fails due to structure mismatch)
    @Volatile
    private var rawLayoutEventBytes: ByteArray? = null

    @Volatile
    private var running = false

    private var eventListenerThread: Thread? = null

    /**
     * Start listening for events from the device daemon using blocking streaming.
     * This matches how Android Studio's TransportServiceProxy does it.
     */
    fun start() {
        if (running) return
        running = true

        println("  [Proxy] Starting event listener with BLOCKING streaming...")

        val request = GetEventsRequest.newBuilder().build()
        println("  [Proxy] Calling getEvents with empty request (all events)")

        // Use a blocking thread like Android Studio does
        eventListenerThread = Thread({
            try {
                println("  [Proxy] Event listener thread started, calling deviceStub.getEvents()...")
                val iterator = deviceStub.getEvents(request)
                println("  [Proxy] Got iterator, entering event loop...")

                var eventCount = 0
                // Android Studio uses: iterator.forEach { it?.let(queue::offer) }
                iterator.forEach { event ->
                    if (event != null && running) {
                        eventCount++
                        println("  [Proxy] EVENT RECEIVED #$eventCount: kind=${event.kind} pid=${event.pid} cmdId=${event.commandId}")
                        processEvent(event)
                    }
                }
                println("  [Proxy] Event loop ended after $eventCount events")
            } catch (e: io.grpc.StatusRuntimeException) {
                if (running) {
                    println("  [Proxy] Event stream status error: ${e.status}")
                }
            } catch (e: Exception) {
                if (running) {
                    println("  [Proxy] Event stream error: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
            }
            println("  [Proxy] Event listener thread ended")
        }, "TransportProxy-EventListener")
        eventListenerThread?.isDaemon = true
        eventListenerThread?.start()

        println("  [Proxy] Event listener thread launched")
    }

    /**
     * Stop the proxy.
     */
    fun stop() {
        running = false
        eventListenerThread?.interrupt()
        eventListenerThread = null
    }

    private fun processEvent(event: Event) {
        println("  [Proxy] Received event: kind=${event.kind} pid=${event.pid} cmdId=${event.commandId} groupId=${event.groupId}")

        // Store event for polling
        eventQueue.offer(event)

        val key = Pair(event.kind, event.groupId)
        eventsByKindAndGroup.getOrPut(key) { mutableListOf() }.add(event)

        // Handle APP_INSPECTION_RESPONSE - complete pending requests
        if (event.kind == Event.Kind.APP_INSPECTION_RESPONSE && event.hasAppInspectionResponse()) {
            val response = event.appInspectionResponse
            // The commandId is inside the appInspectionResponse, not the event
            val commandId = response.commandId
            println("  [Proxy] APP_INSPECTION_RESPONSE for commandId=$commandId status=${response.status}")
            if (response.hasRawResponse()) {
                println("  [Proxy]   hasRawResponse=true dataCase=${response.rawResponse.dataCase}")
            }
            pendingResponses.remove(commandId)?.complete(response)
        }

        // Parse and collect APP_INSPECTION_EVENT layout data
        if (event.kind == Event.Kind.APP_INSPECTION_EVENT && event.hasAppInspectionEvent()) {
            val inspEvent = event.appInspectionEvent
            println("  [Proxy] APP_INSPECTION_EVENT: inspectorId=${inspEvent.inspectorId}")
            if (inspEvent.hasRawEvent()) {
                val rawEvent = inspEvent.rawEvent
                println("  [Proxy]   hasRawEvent=true dataCase=${rawEvent.dataCase}")

                // Parse the raw event as a View Inspector Event
                if (rawEvent.dataCase == com.android.tools.app.inspection.RawEvent.DataCase.CONTENT) {
                    val content = rawEvent.content.toByteArray()
                    rawInspectorEvents.add(content)
                    println("  [Proxy]   Raw content size: ${content.size} bytes")

                    // Save raw event data for debugging
                    try {
                        val eventFile = java.io.File("/tmp/layout-events/event_${rawInspectorEvents.size}_${content.size}.bin")
                        eventFile.writeBytes(content)
                    } catch (e: Exception) {
                        // Ignore
                    }

                    // Try to parse as ViewInspectorEvent
                    try {
                        val viewEvent = ViewInspectorEvent.parseFrom(content)
                        println("  [Proxy]   Parsed ViewInspectorEvent: hasLayoutEvent=${viewEvent.hasLayoutEvent()} hasRootsEvent=${viewEvent.hasRootsEvent()}")
                        println("  [Proxy]   ViewInspectorEvent specializedCase=${viewEvent.specializedCase}")

                        if (viewEvent.hasLayoutEvent()) {
                            val layoutEvent = viewEvent.layoutEvent
                            layoutEvents.add(layoutEvent)
                            println("  [Proxy]   LayoutEvent: hasRootView=${layoutEvent.hasRootView()} hasScreenshot=${layoutEvent.hasScreenshot()}")
                            if (layoutEvent.hasRootView()) {
                                val rootView = layoutEvent.rootView
                                println("  [Proxy]   RootView: hasNode=${rootView.hasNode()} childrenCount=${rootView.childrenCount}")
                                if (rootView.hasNode()) {
                                    val node = rootView.node
                                    println("  [Proxy]   RootNode: id=${node.id} class=${node.className} bounds=${node.bounds}")
                                }
                            }
                            if (layoutEvent.hasScreenshot()) {
                                val screenshot = layoutEvent.screenshot
                                println("  [Proxy]   Screenshot: type=${screenshot.type} size=${screenshot.bytes.size()} bytes")
                            }
                        }

                        if (viewEvent.hasRootsEvent()) {
                            val rootsEvent = viewEvent.rootsEvent
                            windowRootsEvents.add(rootsEvent)
                            println("  [Proxy]   WindowRootsEvent: ids count=${rootsEvent.idsCount}")
                        }

                        // Also try to parse LayoutEvent directly (some responses might not be wrapped in Event)
                        if (!viewEvent.hasLayoutEvent() && !viewEvent.hasRootsEvent()) {
                            println("  [Proxy]   Trying to parse as direct LayoutEvent...")
                            try {
                                val directLayoutEvent = LayoutEvent.parseFrom(content)
                                if (directLayoutEvent.hasRootView() || directLayoutEvent.hasScreenshot()) {
                                    println("  [Proxy]   Direct LayoutEvent: hasRootView=${directLayoutEvent.hasRootView()} hasScreenshot=${directLayoutEvent.hasScreenshot()}")
                                    layoutEvents.add(directLayoutEvent)
                                }
                            } catch (e2: Exception) {
                                // Try as WindowRootsEvent
                                try {
                                    val directRoots = WindowRootsEvent.parseFrom(content)
                                    if (directRoots.idsCount > 0) {
                                        println("  [Proxy]   Direct WindowRootsEvent: ids count=${directRoots.idsCount}")
                                        windowRootsEvents.add(directRoots)
                                    }
                                } catch (e3: Exception) {
                                    // Debug: print first 50 bytes as hex
                                    val hexPreview = content.take(50).joinToString(" ") { "%02x".format(it) }
                                    println("  [Proxy]   Unknown event format, first 50 bytes: $hexPreview")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("  [Proxy]   Failed to parse ViewInspectorEvent: ${e.message}")
                        // Debug: print first 50 bytes as hex
                        val hexPreview = content.take(50).joinToString(" ") { "%02x".format(it) }
                        println("  [Proxy]   Raw first 50 bytes: $hexPreview")

                        // Try to detect event type from first byte and parse directly
                        if (content.isNotEmpty()) {
                            val tag = content[0].toInt() and 0xFF
                            val field = tag shr 3
                            val wireType = tag and 0x7
                            println("  [Proxy]   First byte tag: field=$field wireType=$wireType")

                            if (field == 3 && wireType == 2 && content.size > 100_000) {
                                // Field 3 = layout_event, this is likely a large LayoutEvent
                                println("  [Proxy]   Detected large LayoutEvent (${content.size} bytes)")
                                // Store the FIRST large LayoutEvent - subsequent ones may have different structure
                                if (rawLayoutEventBytes == null) {
                                    println("  [Proxy]   Storing first large LayoutEvent")
                                    rawLayoutEventBytes = content
                                }
                            } else if (field == 4 && wireType == 2) {
                                // Field 4 = properties_event
                                println("  [Proxy]   Detected PropertiesEvent (${content.size} bytes)")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Get all collected layout events.
     */
    fun getLayoutEvents(): List<LayoutEvent> = layoutEvents.toList()

    /**
     * Get all collected window roots events.
     */
    fun getWindowRootsEvents(): List<WindowRootsEvent> = windowRootsEvents.toList()

    /**
     * Get all raw inspector events (for debugging).
     */
    fun getRawInspectorEvents(): List<ByteArray> = rawInspectorEvents.toList()

    /**
     * Get raw layout event bytes (when proto parsing failed).
     */
    fun getRawLayoutEventBytes(): ByteArray? = rawLayoutEventBytes

    /**
     * Clear collected events.
     */
    fun clearEvents() {
        layoutEvents.clear()
        windowRootsEvents.clear()
        rawInspectorEvents.clear()
        rawLayoutEventBytes = null
    }

    /**
     * Send an APP_INSPECTION command and wait for the response.
     * @param timeoutMs Timeout in milliseconds (default 15 seconds)
     */
    suspend fun sendAppInspectionCommand(
        pid: Int,
        sessionId: Long,
        command: com.android.tools.app.inspection.AppInspectionCommand,
        timeoutMs: Long = 15_000
    ): AppInspectionResponse {
        val commandId = command.commandId
        val deferred = CompletableDeferred<AppInspectionResponse>()
        pendingResponses[commandId] = deferred

        // Build transport command
        val transportCommand = Command.newBuilder()
            .setStreamId(streamId)
            .setType(Command.CommandType.APP_INSPECTION)
            .setPid(pid)
            .setCommandId(commandId)
            .setSessionId(sessionId)
            .setAppInspectionCommand(command)
            .build()

        // Execute on device
        deviceStub.execute(ExecuteRequest.newBuilder().setCommand(transportCommand).build())
        println("  [Proxy] Execute completed for command $commandId, waiting for response (timeout=${timeoutMs}ms)...")

        // Wait for response with configurable timeout
        return withTimeout(timeoutMs) {
            println("  [Proxy] Waiting for response to command $commandId...")
            deferred.await()
        }
    }

    // gRPC service implementations for clients

    override fun getVersion(request: VersionRequest, responseObserver: StreamObserver<VersionResponse>) {
        responseObserver.onNext(deviceStub.getVersion(request))
        responseObserver.onCompleted()
    }

    override fun execute(request: ExecuteRequest, responseObserver: StreamObserver<ExecuteResponse>) {
        responseObserver.onNext(deviceStub.execute(request))
        responseObserver.onCompleted()
    }

    override fun getEvents(request: GetEventsRequest, responseObserver: StreamObserver<Event>) {
        // Stream events to client
        Thread {
            try {
                while (running) {
                    val event = eventQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (event != null) {
                        responseObserver.onNext(event)
                    }
                }
                responseObserver.onCompleted()
            } catch (e: Exception) {
                responseObserver.onError(e)
            }
        }.start()
    }

    override fun getEventGroups(request: GetEventGroupsRequest, responseObserver: StreamObserver<GetEventGroupsResponse>) {
        val builder = GetEventGroupsResponse.newBuilder()

        // Find matching events
        val matchingEvents = eventsByKindAndGroup.entries
            .filter { (key, _) -> key.first == request.kind }
            .flatMap { (key, events) ->
                val groupBuilder = EventGroup.newBuilder().setGroupId(key.second)
                events.forEach { groupBuilder.addEvents(it) }
                listOf(groupBuilder.build())
            }

        builder.addAllGroups(matchingEvents)
        responseObserver.onNext(builder.build())
        responseObserver.onCompleted()
    }
}
