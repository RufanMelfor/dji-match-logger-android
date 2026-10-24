package com.djimatchlogger.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.djimatchlogger.ble.BleEnvironment
import com.djimatchlogger.ble.BleScanFailedException
import com.djimatchlogger.ble.BleScanner
import com.djimatchlogger.ble.CameraCommands
import com.djimatchlogger.ble.CameraStatus
import com.djimatchlogger.ble.LastKnownLocation
import com.djimatchlogger.ble.CameraStatusReport
import com.djimatchlogger.ble.CommandType
import com.djimatchlogger.ble.CameraLink
import com.djimatchlogger.ble.DjiCamera
import com.djimatchlogger.ble.LinkState
import com.djimatchlogger.ble.LocalIdentity
import com.djimatchlogger.ble.ScanBlocker
import com.djimatchlogger.data.AppSettings
import com.djimatchlogger.data.CameraSlot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

data class ScanUiState(
    val isScanning: Boolean = false,
    val cameras: List<DjiCamera> = emptyList(),
    val blocker: ScanBlocker? = null,
    val scanErrorCode: Int? = null,
    /** Link state per camera address; absent means never connected. */
    val links: Map<String, LinkState> = emptyMap(),
    /** Frames received per camera address — evidence that the CCCD write took. */
    val framesReceived: Map<String, Int> = emptyMap(),
    /** Last camera_status per address, from the 0x1D02 push. */
    val cameraStatus: Map<String, Int> = emptyMap(),
    /** Everything else from that push: battery, capacity, temperature, sleep. */
    val reports: Map<String, CameraStatusReport> = emptyMap(),
    /** Frames that failed validation, per address. Should stay at zero. */
    val rejectedFrames: Map<String, Int> = emptyMap(),
    /** Which side each camera is on, remembered by address. */
    val slots: Map<String, CameraSlot> = emptyMap(),
    /**
     * Seconds left before the cameras stop of their own accord after the final
     * whistle, or null when nothing is pending.
     */
    val stoppingInSeconds: Int? = null,
    /** How long that wait is, when it starts. */
    val stopDelaySeconds: Int = AppSettings.DEFAULT_STOP_DELAY_SECONDS,
    /** Whether the app is in dark mode (Midnight Aero) or light mode (Daylight Aero). */
    val isDarkMode: Boolean = true,
) {

    fun addressOf(slot: CameraSlot): String? =
        slots.entries.firstOrNull { it.value == slot }?.key

    fun cameraOf(slot: CameraSlot): DjiCamera? =
        addressOf(slot)?.let { address -> cameras.firstOrNull { it.address == address } }

    fun isReady(address: String): Boolean = links[address] == LinkState.Ready
    /**
     * Whether a camera is capturing, according to its own status push rather
     * than to whether a record command was accepted.
     */
    fun isRecording(address: String): Boolean =
        cameraStatus[address]?.let(CameraStatus::isRecording) == true

    val readyCount: Int get() = links.values.count { it == LinkState.Ready }
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val environment = BleEnvironment(application)
    private val scanner = BleScanner(application)
    private val identity = LocalIdentity(application)
    private val location = LastKnownLocation(application)
    private val links = mutableMapOf<String, CameraLink>()
    private val settings = AppSettings(application)
    private var stopJob: Job? = null

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(
            slots = storedSlots(),
            stopDelaySeconds = settings.stopDelaySeconds,
            isDarkMode = settings.isDarkMode,
        )
    }

    val requiredPermissions: List<String> get() = environment.requiredPermissions

    /**
     * Re-reads the preconditions. Worth calling whenever the screen comes back
     * to the foreground: the user may have gone off to switch Bluetooth or
     * location on.
     */
    fun refreshBlocker() {
        val blocker = environment.blocker()
        _uiState.value = _uiState.value.copy(blocker = blocker)
        // Auto-start scan when permissions are granted and hardware is ready,
        // but only if we haven't successfully started a scan or connection yet.
        if (blocker == null && !_uiState.value.isScanning && links.isEmpty()) {
            startScan()
        }
    }

    fun toggleScan() {
        if (_uiState.value.isScanning) stopScan() else startScan()
    }

    private fun startScan() {
        val blocker = environment.blocker()
        if (blocker != null) {
            _uiState.value = _uiState.value.copy(blocker = blocker, isScanning = false)
            return
        }

        _uiState.value = _uiState.value.copy(
            isScanning = true,
            blocker = null,
            scanErrorCode = null,
            cameras = emptyList(),
        )

        scanJob = scanner.cameras()
            .onEach(::merge)
            .catch { cause ->
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    scanErrorCode = (cause as? BleScanFailedException)?.errorCode ?: -1,
                )
            }
            .launchIn(viewModelScope)
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(isScanning = false)
    }

    /**
     * Connects a camera, or drops the link if it is already up.
     *
     * Stops scanning first. A scan running during connection setup competes
     * for the same radio and turns a reliable connect into an intermittent
     * one — the cameras are already found by this point, so there is nothing
     * to lose by stopping.
     */
    fun toggleConnection(camera: DjiCamera) {
        val existing = links[camera.address]
        if (existing != null) {
            when (existing.state.value) {
                LinkState.Disconnected, is LinkState.Failed -> {
                    // Manual retry: stop scan to give the radio a better chance.
                    stopScan()
                    existing.connect()
                }

                else -> existing.disconnect()
            }
            return
        }

        stopScan()
        connect(camera)
    }

    private fun connect(camera: DjiCamera) {
        if (links.containsKey(camera.address)) return

        val link = CameraLink(getApplication(), camera, identity)
        links[camera.address] = link

        link.state
            .onEach { state ->
                putLinkState(camera.address, state)
                // Enabling the CCCD only opens the channel; the camera sends
                // nothing until asked. Subscribe the moment the link is up.
                if (state == LinkState.Ready) {
                    link.send(
                        cmdSet = CameraCommands.CMD_SET,
                        cmdId = CameraCommands.CMD_STATUS_SUBSCRIPTION,
                        cmdType = CommandType.NO_RESPONSE,
                        payload = CameraCommands.statusSubscription(),
                    )
                    // Same clock on both cameras, so their files pair up in an
                    // editor. Queued behind the subscription, which is what the
                    // write queue is for.
                    link.syncTime(location.get())
                }
            }
            .launchIn(viewModelScope)
        link.frames
            .onEach { countFrame(camera.address) }
            .launchIn(viewModelScope)
        link.cameraStatus
            .onEach { status -> status?.let { putCameraStatus(camera.address, it) } }
            .launchIn(viewModelScope)
        link.report
            .onEach { report -> report?.let { putReport(camera.address, it) } }
            .launchIn(viewModelScope)
        link.rejectedFrames
            .onEach { count ->
                _uiState.value = _uiState.value.copy(
                    rejectedFrames = _uiState.value.rejectedFrames + (camera.address to count),
                )
            }
            .launchIn(viewModelScope)

        link.connect()
    }

    private fun putLinkState(address: String, state: LinkState) {
        val nextLinks = _uiState.value.links + (address to state)
        _uiState.value = _uiState.value.copy(links = nextLinks)
        
        // If all assigned cameras are connected, we can stop the scan to save battery.
        val assignedAddresses = _uiState.value.slots.keys
        if (assignedAddresses.isNotEmpty() && assignedAddresses.all { nextLinks[it] == LinkState.Ready }) {
            stopScan()
        }
    }

    /**
     * Starts or stops recording on every camera whose link is ready.
     *
     * Each camera is commanded over its own link with its own sequence
     * number. Nothing here marks them as recording: that only becomes true
     * when their status pushes say so.
     */
    fun toggleRecordingAll() {
        cancelPendingStop()
        setRecordingAll(links.keys.none { _uiState.value.isRecording(it) })
    }

    private fun putReport(address: String, report: CameraStatusReport) {
        _uiState.value = _uiState.value.copy(
            reports = _uiState.value.reports + (address to report),
        )
    }

    private fun putCameraStatus(address: String, status: Int) {
        _uiState.value = _uiState.value.copy(
            cameraStatus = _uiState.value.cameraStatus + (address to status),
        )
    }

    private fun countFrame(address: String) {
        val counts = _uiState.value.framesReceived
        _uiState.value = _uiState.value.copy(
            framesReceived = counts + (address to (counts[address] ?: 0) + 1),
        )
    }

    /**
     * A camera advertises continuously, so keep one entry per address and let
     * later sightings refresh the RSSI.
     *
     * Order is first-seen and stays that way. Sorting by signal strength looks
     * sensible until two cameras sit a couple of dBm apart — then the rows
     * swap places every update, under a finger that is about to tap one of
     * them.
     */
    private fun merge(camera: DjiCamera) {
        val known = _uiState.value.cameras
        val index = known.indexOfFirst { it.address == camera.address }
        val merged = if (index == -1) known + camera else known.toMutableList().apply {
            this[index] = camera
        }
        
        val slot = settings.slotFor(camera.address)
        if (index == -1 && slot == null) {
            settings.assignFreeSlot(camera.address)
        }
        
        _uiState.value = _uiState.value.copy(cameras = merged, slots = storedSlots())

        // Auto-connect: if this is a camera we have assigned a slot to, and 
        // we aren't connected yet, start the connection.
        if (slot != null && !links.containsKey(camera.address)) {
            connect(camera)
        }
    }

    private fun storedSlots(): Map<String, CameraSlot> =
        CameraSlot.entries.mapNotNull { slot ->
            settings.addressFor(slot)?.let { it to slot }
        }.toMap()

    /** Puts the cameras on the other side of the pitch from each other. */
    fun swapSlots() {
        settings.swapSlots()
        _uiState.value = _uiState.value.copy(slots = storedSlots())
    }

    fun setStopDelaySeconds(seconds: Int) {
        settings.stopDelaySeconds = seconds
        _uiState.value = _uiState.value.copy(stopDelaySeconds = settings.stopDelaySeconds)
    }

    fun toggleDarkMode() {
        settings.isDarkMode = !settings.isDarkMode
        _uiState.value = _uiState.value.copy(isDarkMode = settings.isDarkMode)
    }

    /**
     * Stops both cameras, but not yet.
     *
     * Counts down out loud so the operator can see it coming and cancel it —
     * ending a match by accident should not cost the footage after it. The
     * countdown lives in the view model rather than in a composable, so it
     * survives the screen being scrolled away or the app being backgrounded.
     */
    fun stopRecordingAfter(seconds: Int) {
        stopJob?.cancel()
        stopJob = viewModelScope.launch {
            for (left in seconds downTo 1) {
                _uiState.value = _uiState.value.copy(stoppingInSeconds = left)
                delay(1_000)
            }
            _uiState.value = _uiState.value.copy(stoppingInSeconds = null)
            setRecordingAll(false)
        }
    }

    fun cancelPendingStop() {
        stopJob?.cancel()
        stopJob = null
        _uiState.value = _uiState.value.copy(stoppingInSeconds = null)
    }

    private fun setRecordingAll(start: Boolean) {
        links.forEach { (_, link) ->
            if (link.state.value != LinkState.Ready) return@forEach
            link.setRecording(start)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        stopJob?.cancel()
        links.values.forEach { it.disconnect() }
        links.clear()
    }
}
