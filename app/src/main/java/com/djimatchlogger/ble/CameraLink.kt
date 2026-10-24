package com.djimatchlogger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One BLE link to one camera.
 *
 * The connect sequence follows the order `docs/ble-protocol.md` records as
 * working, and the order matters:
 *
 * 1. connect over LE
 * 2. MTU exchange
 * 3. discover **all** services — deliberately not just [GattProfile.SERVICE].
 *    Some camera firmwares refuse notifications unless the full GATT database
 *    has been walked.
 * 4. locate the notify and write characteristics
 * 5. read the notify characteristic's descriptors to get the CCCD handle
 * 6. write `0x0100` to the CCCD
 *
 * Only after step 6 can the camera answer anything, which is why
 * [LinkState.Ready] is not reached at connect time.
 *
 * Nothing here calls `createBond`. The cameras run without BLE encryption and
 * forcing pairing breaks them; if Android offers on its own that is Android's
 * business, but this code never asks.
 *
 * A camera accepts **one** link at a time — including against the original DJI
 * remote — so a refused connection may simply mean something else holds it.
 */
class CameraLink(
    private val context: Context,
    val camera: DjiCamera,
    private val identity: LocalIdentity,
) {

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Validated frames pushed by the camera. */
    private val _frames = MutableSharedFlow<DjiFrame.Parsed>(extraBufferCapacity = 64)
    val frames: SharedFlow<DjiFrame.Parsed> = _frames.asSharedFlow()

    /**
     * Frames that arrived but failed validation. Surfaced rather than
     * swallowed: a climbing count means a degrading link, which is otherwise
     * indistinguishable from a quiet one.
     */
    private val _rejectedFrames = MutableStateFlow(0)
    val rejectedFrames: StateFlow<Int> = _rejectedFrames.asStateFlow()

    /**
     * Last `camera_status` seen in a 0x1D02 push, or null if none yet.
     * This — not the answer to the record command — is what says whether the
     * camera is actually recording.
     */
    private val _cameraStatus = MutableStateFlow<Int?>(null)
    val cameraStatus: StateFlow<Int?> = _cameraStatus.asStateFlow()

    /** Everything from the last 0x1D02 push, not just the status byte. */
    private val _report = MutableStateFlow<CameraStatusReport?>(null)
    val report: StateFlow<CameraStatusReport?> = _report.asStateFlow()

    /**
     * Whether to pull the camera back out of sleep on its own.
     *
     * The cameras power down after about half an hour of being left alone,
     * which during a match is exactly what must not happen. Sleep keeps the
     * BLE link up, so this is answerable: watch for it and wake.
     *
     * Off by default would be safer, but the whole point of the app is that
     * nobody has to walk over to the camera.
     */
    @Volatile
    var keepAwake: Boolean = true

    /**
     * Sequence numbers are per link. Two cameras must not share a counter, or
     * their answers cannot be told apart.
     */
    private val sequence = AtomicInteger(0)

    /**
     * BLE hands over packets, not frames. With a small MTU one frame arrives
     * in several, so accumulate and cut on the declared length.
     */
    private val inbox = ArrayList<Byte>()

    /**
     * Frames waiting to go out.
     *
     * A GATT link carries one write at a time. Handing it a second before the
     * first completes gets that second one refused outright — and since a
     * refused command is indistinguishable from a camera that ignored it, the
     * symptom is a feature that silently does nothing.
     */
    private val outbox = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    private var gatt: BluetoothGatt? = null

    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission") // guarded by BleEnvironment.blocker()
    fun connect() {
        if (_state.value != LinkState.Disconnected && _state.value !is LinkState.Failed) return

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = adapter?.getRemoteDevice(camera.address)
        if (device == null) {
            _state.value = LinkState.Failed("Bluetooth unavailable")
            return
        }

        _state.value = LinkState.Connecting
        gatt = device.connectGatt(
            context,
            /* autoConnect = */ false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
    }

    /**
     * Encodes and writes one command, returning whether the stack accepted it
     * for sending. Acceptance is not delivery, and delivery is not action —
     * for recording, only a later status push tells you what happened.
     *
     * Only valid once [state] is [LinkState.Ready]. BLE serialises writes on
     * a link, so commands fired back-to-back on the same camera can be
     * dropped; the ones here are spaced by user taps and a connect handshake.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun send(
        cmdSet: Int,
        cmdId: Int,
        cmdType: Int,
        payload: ByteArray,
        seq: Int = sequence.incrementAndGet() and 0xFFFF,
    ): Boolean {
        if (gatt == null || writeCharacteristic == null) return false

        val frame = DjiFrame.encode(
            cmdSet = cmdSet,
            cmdId = cmdId,
            cmdType = cmdType,
            seq = seq,
            payload = payload,
        )
        synchronized(outbox) { outbox.addLast(frame) }
        pump()
        return true
    }

    /** Writes the next queued frame, if the link is free to take one. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun pump() {
        val gatt = this.gatt ?: return
        val characteristic = writeCharacteristic ?: return

        val frame = synchronized(outbox) {
            if (writeInFlight) return
            val next = outbox.removeFirstOrNull() ?: return
            writeInFlight = true
            next
        }

        val writeType = if (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, frame, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = frame
            characteristic.writeType = writeType
            gatt.writeCharacteristic(characteristic)
        }

        if (!accepted) {
            // Nothing will call back, so release the slot here or the queue
            // stalls for good.
            synchronized(outbox) { writeInFlight = false }
            pump()
        }
    }

    @SuppressLint("MissingPermission")
    private fun release() {
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        synchronized(inbox) { inbox.clear() }
        synchronized(outbox) {
            outbox.clear()
            writeInFlight = false
        }
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = LinkState.Discovering
                    // Shortens the connection interval to roughly 11-15 ms.
                    // A write waits for its link's next connection event, so
                    // this is the one lever that narrows how far apart two
                    // cameras can act on the same command. It does not make
                    // them simultaneous: their sensors free-run on unrelated
                    // clocks, so the frame grids stay offset regardless.
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // Negotiate the MTU before discovery: raising it afterwards
                    // invalidates what was just discovered on some stacks.
                    if (!gatt.requestMtu(MAX_MTU)) gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = if (status == BluetoothGatt.GATT_SUCCESS) {
                        LinkState.Disconnected
                    } else {
                        LinkState.Failed("Disconnected (status $status)")
                    }
                    release()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // A refused MTU is survivable: the default 23 still carries the
            // frames, just in more packets. Carry on either way.
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = LinkState.Failed("Service discovery failed (status $status)")
                return
            }

            val service = gatt.getService(GattProfile.SERVICE)
            if (service == null) {
                _state.value = LinkState.Failed("Service FFF0 not found")
                return
            }

            val notify = service.getCharacteristic(GattProfile.NOTIFY_CHARACTERISTIC)
            writeCharacteristic = service.getCharacteristic(GattProfile.WRITE_CHARACTERISTIC)
            if (notify == null || writeCharacteristic == null) {
                _state.value = LinkState.Failed("Characteristics FFF4/FFF5 not found")
                return
            }

            val cccd = notify.getDescriptor(GattProfile.CCCD)
            if (cccd == null) {
                _state.value = LinkState.Failed("CCCD not found on FFF4")
                return
            }

            _state.value = LinkState.EnablingNotifications

            // Both halves are needed: this routes the notifications inside
            // Android, the CCCD write tells the camera to send them.
            if (!gatt.setCharacteristicNotification(notify, true)) {
                _state.value = LinkState.Failed("Could not route notifications")
                return
            }
            writeCccd(gatt, cccd)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(outbox) { writeInFlight = false }
            pump()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != GattProfile.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = LinkState.Failed("Enabling notifications failed (status $status)")
                return
            }
            _state.value = LinkState.Handshaking
            startHandshake()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == GattProfile.NOTIFY_CHARACTERISTIC) {
                accept(value)
            }
        }

        @Deprecated("Pre-API 33 callback; the overload above replaces it.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // API 33+ delivers the value through the overload above; without
            // this guard the same frame would arrive twice on newer devices.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == GattProfile.NOTIFY_CHARACTERISTIC) {
                characteristic.value?.let { accept(it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCccd(gatt: BluetoothGatt, cccd: BluetoothGattDescriptor) {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
        } else {
            cccd.value = enable
            gatt.writeDescriptor(cccd)
        }
        if (!accepted) _state.value = LinkState.Failed("CCCD write rejected")
    }

    /**
     * Takes one BLE packet and hands out whatever complete frames it
     * completes.
     *
     * Runs on a binder thread, hence the lock around [inbox]. Leading bytes
     * that are not a SOF are discarded: after a dropped packet the stream is
     * out of step, and resynchronising on 0xAA recovers where waiting would
     * stall forever.
     */
    private fun accept(packet: ByteArray) {
        val complete = mutableListOf<ByteArray>()

        synchronized(inbox) {
            packet.forEach { inbox.add(it) }

            while (true) {
                while (inbox.isNotEmpty() && inbox[0] != DjiFrame.SOF) inbox.removeAt(0)

                val buffer = inbox.toByteArray()
                val declared = DjiFrame.declaredLength(buffer) ?: break
                if (declared < DjiFrame.HEADER_LENGTH + DjiFrame.TAIL_LENGTH) {
                    // Not a length any real frame can have: this SOF was data.
                    inbox.removeAt(0)
                    continue
                }
                if (buffer.size < declared) break

                complete += buffer.copyOfRange(0, declared)
                repeat(declared) { inbox.removeAt(0) }
            }
        }

        complete.forEach { bytes ->
            val frame = DjiFrame.decode(bytes)
            if (frame == null) {
                _rejectedFrames.value = _rejectedFrames.value + 1
            } else {
                if (frame.cmdSet == CameraCommands.CMD_SET &&
                    frame.cmdId == CameraCommands.CMD_STATUS_PUSH
                ) {
                    CameraStatus.fromStatusPush(frame.payload)?.let { _cameraStatus.value = it }
                    Log.d(
                        "DjiStatus",
                        "${camera.advertisedName} len=${frame.payload.size} " +
                            frame.payload.joinToString(" ") { b -> "%02X".format(b) },
                    )
                    CameraStatusReport.from(frame.payload)?.let { report ->
                        _report.value = report
                        if (keepAwake && report.isAsleep) wake()
                    }
                }
                if (frame.cmdSet == CameraCommands.CMD_SET_COMMON &&
                    frame.cmdId == CameraCommands.CMD_CONNECTION
                ) {
                    onHandshakeFrame(frame)
                }
                _frames.tryEmit(frame)
            }
        }
    }

    /**
     * Sets the camera's clock, and its position if the phone knows one.
     *
     * Sent to both cameras so their recordings carry the same wall clock and
     * an editor can pair the files up. It does nothing for frame alignment —
     * the sensors free-run — and there is no acknowledgement to check, so the
     * only way to confirm it landed is to look at a file's timestamp.
     */
    fun syncTime(location: android.location.Location?): Boolean = send(
        cmdSet = CameraCommands.CMD_SET_COMMON,
        cmdId = CameraCommands.CMD_GPS_PUSH,
        cmdType = CommandType.NO_RESPONSE,
        payload = CameraCommands.gpsAndTime(
            utcPlus8 = java.time.ZonedDateTime.now(CameraCommands.CAMERA_ZONE),
            latitudeDegrees = location?.latitude ?: 0.0,
            longitudeDegrees = location?.longitude ?: 0.0,
            heightMetres = location?.altitude ?: 0.0,
            horizontalAccuracyMetres = location?.accuracy ?: 0f,
            // Without a fix, say so rather than invent satellites: a false
            // position would be baked into the video's metadata.
            satelliteNumber = if (location == null) 0 else MIN_PLAUSIBLE_SATELLITES,
        ),
    )

    /**
     * Brings a sleeping camera back.
     *
     * The one command a sleeping camera still accepts. Harmless to send to a
     * camera that is already awake.
     */
    fun wake(): Boolean = send(
        cmdSet = CameraCommands.CMD_SET_COMMON,
        cmdId = CameraCommands.CMD_POWER_MODE,
        cmdType = CommandType.NO_RESPONSE,
        payload = CameraCommands.powerMode(CameraStatusReport.POWER_NORMAL),
    )

    /**
     * Starts or stops recording.
     *
     * The `device_id` in the payload is **ours**, the same one sent in the
     * handshake — not the camera's. The id a camera advertises identifies its
     * model (both Osmo Action 4s say 0x0014), so it cannot address one of two
     * units anyway; the link the frame goes down decides that.
     *
     * A `true` return means the frame was queued, nothing more. Whether the
     * camera actually rolls shows up in [cameraStatus] — and it will not roll
     * at all without an SD card in it.
     */
    fun setRecording(start: Boolean): Boolean = send(
        cmdSet = CameraCommands.CMD_SET,
        cmdId = CameraCommands.CMD_RECORD_CONTROL,
        cmdType = CommandType.NO_RESPONSE,
        payload = CameraCommands.recordControl(identity.deviceId, start = start),
    )

    /**
     * Opens the handshake. Pairs the first time this install has met the
     * camera and reconnects after that — asking to pair again would make the
     * camera prompt its user on every single connect.
     */
    private fun startHandshake() {
        val paired = identity.hasPaired(camera.address)
        send(
            cmdSet = CameraCommands.CMD_SET_COMMON,
            cmdId = CameraCommands.CMD_CONNECTION,
            cmdType = CommandType.WAIT_RESULT,
            payload = CameraCommands.connectionRequest(
                deviceId = identity.deviceId,
                protocolMac = identity.protocolMac,
                verifyMode = if (paired) {
                    CameraCommands.VERIFY_RECONNECT
                } else {
                    CameraCommands.VERIFY_PAIR
                },
            ),
        )
    }

    /**
     * Handles the camera's half of the handshake.
     *
     * The camera does not merely answer: after replying it sends a request of
     * its own carrying its verdict, and that one has to be acknowledged on
     * *its* sequence number before it considers the connection open. Skipping
     * this ACK leaves a link that looks connected and obeys nothing.
     */
    private fun onHandshakeFrame(frame: DjiFrame.Parsed) {
        val isCommand = frame.cmdType and 0x20 == 0
        if (!isCommand) return // The answer to ours; the verdict comes separately.

        val payload = frame.payload
        if (payload.size <= CameraCommands.OFFSET_VERIFY_DATA + 1) return

        val verifyMode = payload[CameraCommands.OFFSET_VERIFY_MODE].toInt() and 0xFF
        if (verifyMode != CameraCommands.VERIFY_CAMERA_REPLY) return

        val verifyData = (payload[CameraCommands.OFFSET_VERIFY_DATA].toInt() and 0xFF) or
            ((payload[CameraCommands.OFFSET_VERIFY_DATA + 1].toInt() and 0xFF) shl 8)
        if (verifyData != 0) {
            _state.value = LinkState.Failed("Camera refused the connection ($verifyData)")
            return
        }

        send(
            cmdSet = CameraCommands.CMD_SET_COMMON,
            cmdId = CameraCommands.CMD_CONNECTION,
            cmdType = CommandType.ACK_NO_RESPONSE,
            payload = CameraCommands.connectionResponse(identity.deviceId),
            seq = frame.seq,
        )

        identity.rememberPairing(camera.address)
        _state.value = LinkState.Ready
    }

    private companion object {
        /** Largest MTU Android will negotiate; the camera may grant less. */
        const val MAX_MTU = 517

        /**
         * Reported when the phone has a position but not how many satellites
         * produced it. Android does not expose that; the field only has to be
         * non-zero for the camera to treat the fix as valid.
         */
        const val MIN_PLAUSIBLE_SATELLITES = 6
    }
}