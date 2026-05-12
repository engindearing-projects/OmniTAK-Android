package soy.engindearing.omnitak.mobile.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.OmniTAKApp
import soy.engindearing.omnitak.mobile.data.GeoMath
import soy.engindearing.omnitak.mobile.domain.tracking.Track
import soy.engindearing.omnitak.mobile.domain.tracking.TrackGpxExporter
import soy.engindearing.omnitak.mobile.domain.tracking.TrackKmlExporter
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalBackground
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * SAR breadcrumb track recording UI. Operators start/stop recording
 * and export saved tracks to GPX (Garmin / OsmAnd / BaseCamp) or KML
 * (CalTopo / Google Earth) via the system Storage Access Framework so
 * the file lands wherever they share — Drive, Downloads, AirDrop.
 *
 * Closes K9Blue's TROP feedback: track recording was iOS-only.
 */
@Composable
fun TracksSection() {
    val app = LocalContext.current.applicationContext as OmniTAKApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isRecording by app.trackRecorder.isRecording.collectAsState()
    val isPaused by app.trackRecorder.isPaused.collectAsState()
    val currentTrack by app.trackRecorder.currentTrack.collectAsState()
    val savedTracks by app.trackStore.tracks.collectAsState()

    // SAF: pending export (id + format), so we can launch the picker
    // with a meaningful default filename and write the right bytes
    // once the user picks a destination.
    var pendingExport by remember { mutableStateOf<PendingTrackExport?>(null) }

    val saveKml = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.google-earth.kml+xml",
        ),
    ) { uri: Uri? ->
        val pending = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val xml = TrackKmlExporter.export(pending.track)
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(xml.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }
    val saveGpx = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri: Uri? ->
        val pending = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val xml = TrackGpxExporter.export(pending.track)
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(xml.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isRecording) {
            val track = currentTrack
            val statusLabel = when {
                isPaused -> "Paused"
                else -> "Recording"
            }
            val distanceLabel = GeoMath.formatDistance(track?.totalDistanceM ?: 0.0)
            val pointCount = track?.points?.size ?: 0
            Text(
                "$statusLabel — $distanceLabel · $pointCount pts",
                color = TacticalAccent,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        app.trackRecorder.stop()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalAccent,
                        contentColor = TacticalBackground,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("Stop & save") }
                OutlinedButton(
                    onClick = {
                        if (isPaused) app.trackRecorder.resume() else app.trackRecorder.pause()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (isPaused) "Resume" else "Pause") }
            }
            OutlinedButton(
                onClick = { app.trackRecorder.discard() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Discard") }
        } else {
            Button(
                onClick = { app.trackRecorder.start() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = TacticalAccent,
                    contentColor = TacticalBackground,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start track recording") }
        }

        if (savedTracks.isEmpty()) {
            Text(
                "No saved tracks yet. Start recording to lay a breadcrumb trail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
        } else {
            for (track in savedTracks) {
                TrackRow(
                    track = track,
                    onExportKml = {
                        pendingExport = PendingTrackExport(track, ExportFormat.KML)
                        saveKml.launch(defaultFilename(track, "kml"))
                    },
                    onExportGpx = {
                        pendingExport = PendingTrackExport(track, ExportFormat.GPX)
                        saveGpx.launch(defaultFilename(track, "gpx"))
                    },
                    onDelete = { app.trackStore.delete(track.id) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    onExportKml: () -> Unit,
    onExportGpx: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalSurface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            track.name,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "${GeoMath.formatDistance(track.totalDistanceM)} · ${track.points.size} pts",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onExportKml,
                modifier = Modifier.weight(1f),
            ) { Text("KML") }
            OutlinedButton(
                onClick = onExportGpx,
                modifier = Modifier.weight(1f),
            ) { Text("GPX") }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f),
            ) { Text("Delete") }
        }
    }
}

private enum class ExportFormat { KML, GPX }

private data class PendingTrackExport(val track: Track, val format: ExportFormat)

private fun defaultFilename(track: Track, extension: String): String {
    val safe = track.name.replace(Regex("[^A-Za-z0-9-_]"), "_")
    return "OmniTAK-Track-$safe.$extension"
}
