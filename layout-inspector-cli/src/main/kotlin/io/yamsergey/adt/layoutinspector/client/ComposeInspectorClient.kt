package io.yamsergey.adt.layoutinspector.client

import com.google.protobuf.CodedInputStream
import layoutinspector.compose.inspection.Command
import layoutinspector.compose.inspection.Response
import layoutinspector.compose.inspection.GetComposablesCommand
import layoutinspector.compose.inspection.GetAllParametersCommand
import layoutinspector.compose.inspection.GetComposablesResponse
import layoutinspector.compose.inspection.GetAllParametersResponse
import layoutinspector.compose.inspection.UpdateSettingsCommand
import layoutinspector.compose.inspection.UpdateSettingsResponse
import kotlinx.coroutines.flow.Flow

/**
 * Client for communicating with the Compose Layout Inspector agent on the device.
 */
class ComposeInspectorClient(
    private val messenger: InspectorMessenger
) {
    companion object {
        const val INSPECTOR_ID = "layoutinspector.compose.inspection"
    }

    /**
     * Get the composable tree for a view.
     *
     * @param rootViewId The ID of the root view containing composables
     * @param generation The generation number for this request
     * @param extractAllParameters If true, extract all parameters in one request
     */
    suspend fun getComposables(
        rootViewId: Long,
        generation: Int,
        extractAllParameters: Boolean = false
    ): GetComposablesResponse {
        val command = Command.newBuilder()
            .setGetComposablesCommand(
                GetComposablesCommand.newBuilder()
                    .setRootViewId(rootViewId)
                    .setGeneration(generation)
                    .setExtractAllParameters(extractAllParameters)
                    .build()
            )
            .build()
        val response = sendCommand(command)
        return response.getComposablesResponse
    }

    /**
     * Get all parameters for composables in a view.
     *
     * @param rootViewId The ID of the root view
     * @param generation The generation number for this request
     */
    suspend fun getAllParameters(
        rootViewId: Long,
        generation: Int = 0
    ): GetAllParametersResponse {
        val command = Command.newBuilder()
            .setGetAllParametersCommand(
                GetAllParametersCommand.newBuilder()
                    .setRootViewId(rootViewId)
                    .setGeneration(generation)
                    .build()
            )
            .build()
        val response = sendCommand(command)
        return response.getAllParametersResponse
    }

    /**
     * Update inspector settings.
     *
     * @param includeRecomposeCounts Whether to include recomposition counts
     * @param delayParameterExtractions Whether to delay parameter extractions
     */
    suspend fun updateSettings(
        includeRecomposeCounts: Boolean = false,
        delayParameterExtractions: Boolean = false
    ): UpdateSettingsResponse {
        val command = Command.newBuilder()
            .setUpdateSettingsCommand(
                UpdateSettingsCommand.newBuilder()
                    .setIncludeRecomposeCounts(includeRecomposeCounts)
                    .setDelayParameterExtractions(delayParameterExtractions)
                    .build()
            )
            .build()
        val response = sendCommand(command)
        return response.updateSettingsResponse
    }

    /**
     * Send a command to the inspector and get the response.
     * Uses increased recursion limit for deep compose trees.
     */
    private suspend fun sendCommand(command: Command): Response {
        val responseBytes = messenger.sendRawCommand(command.toByteArray())
        // Increase recursion limit for deep Compose trees
        val inputStream = CodedInputStream.newInstance(responseBytes).apply {
            setRecursionLimit(Int.MAX_VALUE)
        }
        return Response.parseFrom(inputStream)
    }

    /**
     * Get the event flow from the inspector.
     */
    fun eventFlow(): Flow<ByteArray> = messenger.eventFlow
}
