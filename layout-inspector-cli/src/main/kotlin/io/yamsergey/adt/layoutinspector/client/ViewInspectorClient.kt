package io.yamsergey.adt.layoutinspector.client

import com.android.tools.idea.layoutinspector.view.inspection.Command
import com.android.tools.idea.layoutinspector.view.inspection.Response
import com.android.tools.idea.layoutinspector.view.inspection.StartFetchCommand
import com.android.tools.idea.layoutinspector.view.inspection.StopFetchCommand
import com.android.tools.idea.layoutinspector.view.inspection.CaptureSnapshotCommand
import com.android.tools.idea.layoutinspector.view.inspection.GetPropertiesCommand
import com.android.tools.idea.layoutinspector.view.inspection.Screenshot
import com.android.tools.idea.layoutinspector.view.inspection.CaptureSnapshotResponse
import com.android.tools.idea.layoutinspector.view.inspection.GetPropertiesResponse
import kotlinx.coroutines.flow.Flow

/**
 * Client for communicating with the View Layout Inspector agent on the device.
 */
class ViewInspectorClient(
    private val messenger: InspectorMessenger
) {
    companion object {
        const val INSPECTOR_ID = "layoutinspector.view.inspection"
    }

    /**
     * Start fetching layout data from the device.
     *
     * @param continuous If true, continuously stream layout updates. If false, fetch once.
     */
    suspend fun startFetch(continuous: Boolean = false): Response {
        val command = Command.newBuilder()
            .setStartFetchCommand(
                StartFetchCommand.newBuilder()
                    .setContinuous(continuous)
                    .build()
            )
            .build()
        return sendCommand(command)
    }

    /**
     * Stop fetching layout data.
     */
    suspend fun stopFetch(): Response {
        val command = Command.newBuilder()
            .setStopFetchCommand(StopFetchCommand.getDefaultInstance())
            .build()
        return sendCommand(command)
    }

    /**
     * Capture a complete snapshot of the current layout.
     *
     * @param screenshotType The type of screenshot to capture (SKP or BITMAP)
     */
    suspend fun captureSnapshot(screenshotType: Screenshot.Type = Screenshot.Type.SKP): CaptureSnapshotResponse {
        val command = Command.newBuilder()
            .setCaptureSnapshotCommand(
                CaptureSnapshotCommand.newBuilder()
                    .setScreenshotType(screenshotType)
                    .build()
            )
            .build()
        val response = sendCommand(command)
        return response.captureSnapshotResponse
    }

    /**
     * Get detailed properties for a specific view.
     *
     * @param rootViewId The ID of the root view
     * @param viewId The ID of the view to get properties for
     */
    suspend fun getProperties(rootViewId: Long, viewId: Long): GetPropertiesResponse {
        val command = Command.newBuilder()
            .setGetPropertiesCommand(
                GetPropertiesCommand.newBuilder()
                    .setRootViewId(rootViewId)
                    .setViewId(viewId)
                    .build()
            )
            .build()
        val response = sendCommand(command)
        return response.getPropertiesResponse
    }

    /**
     * Send a command to the inspector and get the response.
     */
    private suspend fun sendCommand(command: Command): Response {
        val responseBytes = messenger.sendRawCommand(command.toByteArray())
        return Response.parseFrom(responseBytes)
    }

    /**
     * Get the event flow from the inspector.
     */
    fun eventFlow(): Flow<ByteArray> = messenger.eventFlow
}
