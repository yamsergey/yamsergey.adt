package io.yamsergey.adt.layoutinspector.snapshot

import com.android.tools.idea.layoutinspector.view.inspection.CaptureSnapshotResponse
import com.android.tools.idea.layoutinspector.view.inspection.FoldEvent
import layoutinspector.compose.inspection.GetComposablesResponse
import layoutinspector.compose.inspection.GetAllParametersResponse
import layoutinspector.snapshots.Metadata
import layoutinspector.snapshots.Snapshot
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.nio.file.Path

/**
 * Handles saving layout inspector snapshots to files.
 */
class SnapshotSaver {

    companion object {
        const val SNAPSHOT_VERSION = "4"
        const val CLI_VERSION = "1.0.0"
    }

    /**
     * Save a snapshot to a file.
     *
     * @param path The path to save the snapshot to
     * @param viewSnapshot The view hierarchy snapshot
     * @param composeInfo Map of view ID to (composables, parameters) pairs
     * @param metadata The snapshot metadata
     * @param foldInfo Optional fold information for foldable devices
     */
    fun saveSnapshot(
        path: Path,
        viewSnapshot: CaptureSnapshotResponse,
        composeInfo: Map<Long, Pair<GetComposablesResponse, GetAllParametersResponse>>,
        metadata: SnapshotMetadata,
        foldInfo: FoldEvent? = null
    ) {
        val metadataProto = Metadata.newBuilder()
            .setApiLevel(metadata.apiLevel)
            .setProcessName(metadata.processName)
            .setContainsCompose(composeInfo.isNotEmpty())
            .setLiveDuringCapture(false)
            .setSource(Metadata.Source.CLI)
            .setSourceVersion(CLI_VERSION)
            .setDpi(metadata.dpi)
            .setFontScale(metadata.fontScale)
            .setScreenWidth(metadata.screenWidth)
            .setScreenHeight(metadata.screenHeight)
            .build()

        val snapshotBuilder = Snapshot.newBuilder()
            .setViewSnapshot(viewSnapshot)

        composeInfo.forEach { (viewId, info) ->
            val (composables, parameters) = info
            snapshotBuilder.addComposeInfo(
                Snapshot.ComposeInfo.newBuilder()
                    .setViewId(viewId)
                    .setComposables(composables)
                    .setComposeParameters(parameters)
                    .build()
            )
        }

        if (foldInfo != null) {
            snapshotBuilder.setFoldInfo(foldInfo)
        }

        val snapshot = snapshotBuilder.build()

        // Write to file using the Android Studio snapshot format
        FileOutputStream(path.toFile()).use { fos ->
            DataOutputStream(fos).use { dos ->
                // Write version header as JSON
                val versionJson = """{"version":"$SNAPSHOT_VERSION"}"""
                dos.writeInt(versionJson.length)
                dos.writeBytes(versionJson)

                // Write metadata proto (delimited)
                metadataProto.writeDelimitedTo(fos)

                // Write snapshot proto (delimited)
                snapshot.writeDelimitedTo(fos)
            }
        }
    }
}

/**
 * Metadata for a snapshot.
 */
data class SnapshotMetadata(
    val apiLevel: Int,
    val processName: String,
    val dpi: Int = 420,
    val fontScale: Float = 1.0f,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400
)
