package com.djimatchlogger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.remember
import com.djimatchlogger.data.CameraSlot
import com.djimatchlogger.ui.match.AppHeader
import com.djimatchlogger.ui.match.CameraState
import com.djimatchlogger.ui.match.CameraSummary
import com.djimatchlogger.ui.match.MatchActions
import com.djimatchlogger.ui.match.MatchScreen
import com.djimatchlogger.ui.match.MatchViewModel
import com.djimatchlogger.ui.match.SetupScreen
import com.djimatchlogger.ui.match.metrics
import com.djimatchlogger.ui.scan.ScanViewModel
import com.djimatchlogger.ui.scan.ScanScreen
import com.djimatchlogger.ui.theme.DJIMatchLoggerTheme
import com.djimatchlogger.ui.theme.MatchPalette
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scanViewModel: ScanViewModel = viewModel()
            val scanState by scanViewModel.uiState.collectAsStateWithLifecycle()
            
            DJIMatchLoggerTheme(darkTheme = scanState.isDarkMode) {
                AppTabs(scanViewModel = scanViewModel)
            }
        }
    }
}

private enum class Tab { MATCH, CONFIG }

/**
 * Hands a file to whatever the operator picks — Quick Share, mail, a chat.
 *
 * Through a FileProvider, because a `file://` URI has been refused by Android
 * since API 24. The receiving app gets a one-off read grant on this URI and
 * nothing else.
 */
private fun shareFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.config_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.config_share_match)),
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share file: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Two screens, one activity.
 *
 * Both view models are scoped to the activity, so leaving the camera tab does
 * not tear down the BLE links — that would drop a recording mid-match. The
 * screen that is off view simply stops composing.
 */
@Composable
private fun AppTabs(
    scanViewModel: ScanViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableStateOf(Tab.MATCH) }
    var fullscreen by rememberSaveable { mutableStateOf(true) }

    // Hides the status and navigation bars, which is most of what is between
    // the match clock and the edge of a landscape screen. The bars come back
    // on a swipe from the edge, so the phone is never actually locked into it.
    val view = LocalView.current
    LaunchedEffect(fullscreen) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Both view models are scoped to the activity, so this is the same
    // instance the camera tab uses. That is what lets the match screen show
    // the links without either screen knowing about the other.
    val matchViewModel: MatchViewModel = viewModel()
    val scanState by scanViewModel.uiState.collectAsStateWithLifecycle()

    fun cameraState(slot: CameraSlot) = scanState.addressOf(slot)?.let { address ->
        CameraState(
            connected = scanState.isReady(address),
            recording = scanState.isRecording(address),
        )
    } ?: CameraState()

    val cameras = CameraSummary(
        left = cameraState(CameraSlot.LEFT),
        right = cameraState(CameraSlot.RIGHT),
        stoppingInSeconds = scanState.stoppingInSeconds,
    )

    // The operator starts the cameras by hand, minutes before kick-off. What
    // is logged is the moment the cameras themselves report they are rolling,
    // not the moment the button was pressed — the gap between that and the
    // whistle is exactly what makes the footage line up afterwards.
    LaunchedEffect(cameras.anyRecording) {
        if (cameras.anyRecording) matchViewModel.videoStarted(confirmed = true)
    }

    // The whistle is not the end of what is worth filming. Undoing the end of
    // a match calls the wait off again.
    val match by matchViewModel.match.collectAsStateWithLifecycle()
    val teams by matchViewModel.teams.collectAsStateWithLifecycle()
    val savedFile by matchViewModel.savedFile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSetup by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(match.ended) {
        if (match.ended) {
            scanViewModel.stopRecordingAfter(scanState.stopDelaySeconds)
        } else {
            scanViewModel.cancelPendingStop()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MatchPalette.Page)
    ) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val size = metrics(landscape)

        AppHeader(
            match = match,
            actions = remember(matchViewModel, scanViewModel) {
                MatchActions(
                    goal = matchViewModel::goal,
                    yellowCard = matchViewModel::yellowCard,
                    redCard = matchViewModel::redCard,
                    startNextPeriod = matchViewModel::startNextPeriod,
                    togglePause = matchViewModel::togglePause,
                    endMatch = matchViewModel::endMatch,
                    stepAddedTime = matchViewModel::stepAddedTime,
                    undo = matchViewModel::undo,
                    note = matchViewModel::note,
                    setMeta = matchViewModel::setMeta,
                    newMatch = matchViewModel::newMatch,
                    toggleVideo = scanViewModel::toggleRecordingAll,
                )
            },
            cameras = cameras,
            size = size,
            landscape = landscape,
            fullscreen = fullscreen,
            onToggleFullscreen = { fullscreen = !fullscreen },
            selectedTab = tab.name,
            onTabSelect = { tab = Tab.valueOf(it) },
            onEdit = { showSetup = true }
        )

        when (tab) {
            Tab.MATCH -> MatchScreen(
                modifier = Modifier.weight(1f),
                viewModel = matchViewModel,
                cameras = cameras,
                onToggleVideo = scanViewModel::toggleRecordingAll,
            )

            Tab.CONFIG -> ScanScreen(
                modifier = Modifier.weight(1f),
                viewModel = scanViewModel,
                teamCount = teams.size,
                teamsFilePath = matchViewModel.teamsFilePath,
                onImportTeams = { uri ->
                    matchViewModel.importTeams(uri) { result ->
                        val message = result.fold(
                            onSuccess = { context.getString(R.string.config_teams_imported, it) },
                            onFailure = { context.getString(R.string.config_teams_import_failed) },
                        )
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
                matchFilePath = savedFile ?: matchViewModel.latestMatchFile(),
                onShareMatch = {
                    val path = savedFile ?: matchViewModel.latestMatchFile()
                    if (path == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.config_share_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        shareFile(context, File(path))
                    }
                },
            )
        }
    }

    if (showSetup) {
        SetupScreen(
            meta = match.meta,
            onDismiss = { showSetup = false },
            onSave = {
                matchViewModel.setMeta(it)
                showSetup = false
            },
            knownTeams = teams,
        )
    }
}
