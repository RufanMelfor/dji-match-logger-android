package com.djimatchlogger.ui.scan

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.djimatchlogger.R
import com.djimatchlogger.ble.CameraLabels
import com.djimatchlogger.ble.CameraStatusReport
import com.djimatchlogger.ble.DjiCamera
import com.djimatchlogger.ble.LinkState
import com.djimatchlogger.ble.ScanBlocker
import com.djimatchlogger.data.AppSettings
import com.djimatchlogger.data.CameraSlot
import com.djimatchlogger.ui.theme.MatchPalette

/** How far one press of the delay buttons moves it. */
private const val STOP_DELAY_STEP_SECONDS = 5

private const val DASH = "—"

/**
 * The configuration page: the cameras, and the settings that outlive a match.
 *
 * Laid out as one panel per concern,
 * each camera showing what it is doing rather than what it is called. The
 * lines that matter beside a pitch are resolution and frame rate, how long it
 * has been recording, and temperature: between them they say whether the
 * footage will be usable and whether the camera is about to stop making it.
 */
@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel(),
    teamCount: Int = 0,
    teamsFilePath: String = "",
    onImportTeams: (Uri) -> Unit = {},
    matchFilePath: String? = null,
    onShareMatch: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Bluetooth and location get switched on outside this app, so re-read the
    // preconditions every time the screen comes back rather than only once.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBlocker()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshBlocker() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MatchPalette.Page)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.config_title),
                style = MaterialTheme.typography.titleMedium,
                color = MatchPalette.Text,
            )
            Spacer(Modifier.weight(1f))
            FlatButton(
                label = stringResource(
                    if (state.isScanning) R.string.scan_stop else R.string.scan_start,
                ),
                color = if (state.isScanning) MatchPalette.Pause else MatchPalette.NextPeriod,
                onClick = viewModel::toggleScan,
            )
        }

        state.blocker?.let { blocker ->
            BlockerPanel(
                blocker = blocker,
                onGrant = {
                    permissionLauncher.launch(viewModel.requiredPermissions.toTypedArray())
                },
                onRecheck = viewModel::refreshBlocker,
            )
        }

        state.scanErrorCode?.let { code ->
            Text(
                text = stringResource(R.string.scan_failed, code),
                color = MatchPalette.Danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ConfigPanel(stringResource(R.string.panel_cameras)) {
            val content = @Composable {
                CameraSlot.entries.forEach { slot ->
                    CameraBlock(
                        slot = slot,
                        camera = state.cameraOf(slot),
                        state = state,
                        onToggleConnection = { viewModel.toggleConnection(it) },
                        modifier = if (landscape) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                    )
                }
            }

            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            } else {
                content()
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlatButton(
                    label = stringResource(R.string.config_swap_sides),
                    color = MatchPalette.Neutral,
                    enabled = state.slots.size > 1,
                    onClick = viewModel::swapSlots,
                )
                Text(
                    text = stringResource(R.string.config_swap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MatchPalette.Muted,
                )
            }
            if (state.cameras.isEmpty()) {
                Text(
                    text = stringResource(
                        if (state.isScanning) R.string.scan_empty else R.string.scan_idle,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MatchPalette.Muted,
                )
            }
        }

        ConfigPanel(stringResource(R.string.config_match_files)) {
            Text(
                text = matchFilePath?.substringAfterLast('/')
                    ?: stringResource(R.string.config_no_match_file),
                style = MaterialTheme.typography.bodySmall,
                color = if (matchFilePath == null) MatchPalette.Muted else MatchPalette.Text,
            )
            FlatButton(
                label = stringResource(R.string.config_share_match),
                color = MatchPalette.Go,
                enabled = matchFilePath != null,
                onClick = onShareMatch,
            )
            Text(
                text = stringResource(R.string.config_match_files_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MatchPalette.Muted,
            )
        }

        ConfigPanel(stringResource(R.string.config_teams)) {
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let(onImportTeams) }

            Text(
                text = stringResource(R.string.config_teams_count, teamCount),
                style = MaterialTheme.typography.bodySmall,
                color = MatchPalette.Text,
            )
            Text(
                text = teamsFilePath,
                style = MaterialTheme.typography.labelSmall,
                color = MatchPalette.Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlatButton(
                    label = stringResource(R.string.config_teams_import),
                    color = MatchPalette.Home,
                    // Any MIME type: a .json copied off a PC arrives as
                    // application/octet-stream often enough that filtering on
                    // application/json hides the file the operator is looking at.
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
            }
            Text(
                text = stringResource(R.string.config_teams_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MatchPalette.Muted,
            )
        }

        ConfigPanel(stringResource(R.string.config_keep_recording)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.config_seconds, state.stopDelaySeconds),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MatchPalette.Text,
                )
                Spacer(Modifier.weight(1f))
                FlatButton(
                    label = stringResource(R.string.match_added_time_less),
                    color = MatchPalette.Neutral,
                    enabled = state.stopDelaySeconds > 0,
                    onClick = {
                        viewModel.setStopDelaySeconds(
                            state.stopDelaySeconds - STOP_DELAY_STEP_SECONDS,
                        )
                    },
                )
                FlatButton(
                    label = stringResource(R.string.match_added_time_more),
                    color = MatchPalette.Neutral,
                    enabled = state.stopDelaySeconds < AppSettings.MAX_STOP_DELAY_SECONDS,
                    onClick = {
                        viewModel.setStopDelaySeconds(
                            state.stopDelaySeconds + STOP_DELAY_STEP_SECONDS,
                        )
                    },
                )
            }
            Text(
                text = stringResource(R.string.config_keep_recording_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MatchPalette.Muted,
            )
        }

        ConfigPanel(stringResource(R.string.config_theme)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FlatButton(
                    label = stringResource(if (state.isDarkMode) R.string.config_theme_light else R.string.config_theme_dark),
                    color = if (state.isDarkMode) MatchPalette.NextPeriod else MatchPalette.Home,
                    onClick = viewModel::toggleDarkMode,
                )
                Text(
                    text = if (state.isDarkMode) stringResource(R.string.config_theme_dark) else stringResource(R.string.config_theme_light),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MatchPalette.Text,
                )
            }
        }
    }
}

/**
 * One camera, under the side it is on rather than the name it advertises.
 *
 * Both Osmo Action 4s advertise the same device id, so the advertised name
 * says nothing about which is which. The side does, and it is remembered by
 * Bluetooth address.
 */
@Composable
private fun CameraBlock(
    slot: CameraSlot,
    camera: DjiCamera?,
    state: ScanUiState,
    modifier: Modifier = Modifier,
    onToggleConnection: (DjiCamera) -> Unit,
) {
    val link = camera?.let { state.links[it.address] } ?: LinkState.Disconnected
    val report = camera?.let { state.reports[it.address] }
    val recording = camera != null && state.isRecording(camera.address)

    Column(
        modifier = modifier
            .background(MatchPalette.Page, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        when {
                            recording -> MatchPalette.RedCard
                            link == LinkState.Ready -> MatchPalette.Go
                            else -> MatchPalette.Neutral
                        },
                        CircleShape,
                    ),
            )
            Text(
                text = stringResource(
                    if (slot == CameraSlot.LEFT) R.string.cam_left else R.string.cam_right,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MatchPalette.Text,
            )
            if (recording) {
                Text(
                    text = stringResource(R.string.cam_rec),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MatchPalette.RedCard,
                )
            }
            Spacer(Modifier.weight(1f))
            if (camera != null) {
                FlatButton(
                    label = stringResource(
                        if (link == LinkState.Ready) {
                            R.string.config_disconnect
                        } else {
                            R.string.config_connect
                        },
                    ),
                    color = if (link == LinkState.Ready) {
                        MatchPalette.Neutral
                    } else {
                        MatchPalette.Home
                    },
                    onClick = { onToggleConnection(camera) },
                )
            }
        }

        Text(
            text = linkText(link),
            style = MaterialTheme.typography.labelMedium,
            color = if (link == LinkState.Ready) MatchPalette.Go else MatchPalette.Muted,
        )

        if (camera == null) {
            Text(
                text = stringResource(R.string.config_no_camera),
                style = MaterialTheme.typography.labelSmall,
                color = MatchPalette.Muted,
            )
            return@Column
        }

        // The lines that decide whether the footage is any good, and whether
        // the camera is about to stop producing it.
        ParameterLine(
            label = stringResource(R.string.config_format),
            value = report?.let(::formatOf) ?: DASH,
        )
        ParameterLine(
            label = stringResource(R.string.config_recorded),
            value = report?.recordTimeSeconds?.let(CameraLabels::duration) ?: DASH,
        )
        ParameterLine(
            label = stringResource(R.string.config_temperature),
            value = report?.temperature?.let(CameraLabels::temperature) ?: DASH,
            valueColor = when (report?.temperature) {
                null, 0 -> MatchPalette.Text
                1 -> MatchPalette.Pause
                else -> MatchPalette.Danger
            },
        )
        ParameterLine(
            label = stringResource(R.string.config_card_free),
            value = report?.remainingCapacityMb?.let(CameraLabels::capacity) ?: DASH,
        )
        ParameterLine(
            label = stringResource(R.string.config_battery),
            value = report?.batteryPercent?.let { "$it%" } ?: DASH,
        )

        // Sleep is not power-off: the link stays up and status keeps coming,
        // but the camera refuses everything except being woken. Without this
        // line that looks identical to a camera that is simply idle.
        if (report?.isAsleep == true) {
            Text(
                text = stringResource(R.string.camera_asleep),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MatchPalette.Pause,
            )
        }

        // Everything else the push carries. Folded away because it is for
        // working out why something is wrong, not for a match.
        var expanded by remember { mutableStateOf(false) }
        Text(
            text = stringResource(
                if (expanded) R.string.details_hide else R.string.details_show,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MatchPalette.HomeText,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        )
        if (expanded) {
            AllParameters(
                report = report,
                framesReceived = state.framesReceived[camera.address] ?: 0,
                rejectedFrames = state.rejectedFrames[camera.address] ?: 0,
            )
        }
    }
}

@Composable
private fun AllParameters(
    report: CameraStatusReport?,
    framesReceived: Int,
    rejectedFrames: Int,
) {
    ParameterLine(
        label = stringResource(R.string.detail_mode),
        value = report?.cameraMode?.let(CameraLabels::cameraMode) ?: DASH,
    )
    ParameterLine(
        label = stringResource(R.string.detail_status),
        value = report?.cameraStatus?.let(CameraLabels::cameraStatus) ?: DASH,
    )
    ParameterLine(
        label = stringResource(R.string.detail_eis),
        value = report?.eisMode?.let(CameraLabels::eis) ?: DASH,
    )
    ParameterLine(
        label = stringResource(R.string.detail_remaining),
        value = report?.remainingRecordSeconds?.let(CameraLabels::longDuration) ?: DASH,
    )
    ParameterLine(
        label = stringResource(R.string.detail_photos),
        value = report?.remainingPhotos?.toString() ?: DASH,
    )
    ParameterLine(
        label = stringResource(R.string.detail_power),
        value = report?.powerMode?.let(CameraLabels::powerMode) ?: DASH,
    )
    ParameterLine(
        label = stringResource(R.string.detail_user_mode),
        value = report?.userMode?.toString() ?: DASH,
    )
    ParameterLine(
        label = stringResource(R.string.detail_frames),
        value = framesReceived.toString(),
    )
    ParameterLine(
        label = stringResource(R.string.detail_rejected),
        value = rejectedFrames.toString(),
        // Anything but zero here means frames are arriving that do not
        // validate, and every one of them was silently thrown away.
        valueColor = if (rejectedFrames > 0) MatchPalette.Danger else MatchPalette.Text,
    )
}

/** Resolution and frame rate on one line, as the camera's own menu shows it. */
private fun formatOf(report: CameraStatusReport): String {
    val resolution = report.videoResolution?.let(CameraLabels::resolution) ?: return DASH
    val fps = report.fpsIndex?.let(CameraLabels::fps) ?: return resolution
    return "$resolution · $fps"
}

@Composable
private fun ParameterLine(label: String, value: String, valueColor: Color = MatchPalette.Text) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MatchPalette.Muted,
            modifier = Modifier.width(118.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun linkText(link: LinkState): String = stringResource(
    when (link) {
        LinkState.Disconnected -> R.string.link_disconnected
        LinkState.Connecting -> R.string.link_connecting
        LinkState.Discovering -> R.string.link_discovering
        LinkState.EnablingNotifications -> R.string.link_enabling
        LinkState.Handshaking -> R.string.link_handshaking
        LinkState.Ready -> R.string.link_ready
        is LinkState.Failed -> R.string.link_failed_short
    },
)

@Composable
private fun BlockerPanel(
    blocker: ScanBlocker,
    onGrant: () -> Unit,
    onRecheck: () -> Unit,
) {
    ConfigPanel(stringResource(R.string.config_blocked)) {
        Text(
            text = stringResource(
                when (blocker) {
                    is ScanBlocker.MissingPermissions -> R.string.blocker_permission
                    ScanBlocker.BluetoothDisabled -> R.string.blocker_bluetooth
                    ScanBlocker.LocationServicesDisabled -> R.string.blocker_location
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MatchPalette.Text,
        )
        when (blocker) {
            is ScanBlocker.MissingPermissions -> FlatButton(
                label = stringResource(R.string.blocker_permission_action),
                color = MatchPalette.Home,
                onClick = onGrant,
            )
            // Bluetooth and location are switched somewhere else entirely, so
            // all this screen can offer is to look again afterwards.
            else -> FlatButton(
                label = stringResource(R.string.blocker_recheck),
                color = MatchPalette.Home,
                onClick = onRecheck,
            )
        }
    }
}

@Composable
private fun ConfigPanel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MatchPalette.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, MatchPalette.PanelBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MatchPalette.PanelTitle,
        )
        content()
    }
}

@Composable
private fun FlatButton(
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val gradientColor = when (color) {
        MatchPalette.Home -> MatchPalette.HomeEnd
        MatchPalette.NextPeriod -> MatchPalette.NextPeriodEnd
        MatchPalette.Pause -> MatchPalette.PauseEnd
        MatchPalette.EndMatch -> MatchPalette.EndMatchEnd
        MatchPalette.Go -> MatchPalette.GoEnd
        MatchPalette.Neutral -> MatchPalette.NeutralEnd
        else -> color.copy(alpha = 0.8f)
    }

    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (enabled) {
                    Modifier
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(color.copy(alpha = 0.9f), color, gradientColor)
                            )
                        )
                        .clickable(onClick = onClick)
                } else {
                    Modifier.background(MatchPalette.Disabled)
                }
            )
            .border(
                width = 1.dp,
                color = if (enabled) Color.White.copy(alpha = 0.3f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1
        )
    }
}
