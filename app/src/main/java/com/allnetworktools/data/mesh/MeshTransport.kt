package com.allnetworktools.data.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A link to a Meshtastic node: carries ToRadio bytes out and hands every FromRadio message to [onFromRadio]. */
interface MeshTransport {
    /** Opens the link; null on success, else the reason, in French. [onStep] reports progress. */
    suspend fun open(onStep: (String) -> Unit): String?
    suspend fun send(toRadio: ByteArray): Boolean
    fun close()
}

/**
 * Bluetooth LE, as the official apps do it (Meshtastic-Android core/ble): write ToRadio to its characteristic, then read
 * FromRadio until it comes back empty, again each time FromNum notifies that something new is waiting.
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val onFromRadio: (ByteArray) -> Unit,
    private val onLost: (String) -> Unit,
) : MeshTransport {
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private val ops = Mutex() // one GATT operation at a time
    private val drainLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    @Volatile private var connected = CompletableDeferred<Boolean>()
    @Volatile private var closed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) connected.complete(true)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connected.isCompleted) connected.complete(false)
                pending?.complete(null)
                if (!closed) onLost(if (status == 19 || status == 8) "Le nœud est hors de portée ou s'est éteint" else "Liaison Bluetooth perdue (code $status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { pending?.complete(mtu) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pending?.complete(status == BluetoothGatt.GATT_SUCCESS) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pending?.complete(status == BluetoothGatt.GATT_SUCCESS) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { pending?.complete(status == BluetoothGatt.GATT_SUCCESS) }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Android 12")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == FROMNUM) drain()
        }

        @Deprecated("Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == FROMNUM) drain()
        }
    }

    /** Runs one GATT operation and waits for its callback. */
    private suspend fun <T> op(timeoutMs: Long = 8000, start: (BluetoothGatt) -> Boolean): T? = ops.withLock {
        val g = gatt ?: return@withLock null
        val d = CompletableDeferred<Any?>()
        pending = d
        if (!start(g)) { pending = null; return@withLock null }
        @Suppress("UNCHECKED_CAST")
        val r = withTimeoutOrNull(timeoutMs) { d.await() } as T?
        pending = null
        r
    }

    /** Pairs with the node first: its firmware refuses to talk to an unpaired phone. Android asks for the PIN. */
    private suspend fun bond(onStep: (String) -> Unit): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        onStep("Appairage : entrez le code PIN affiché par le nœud (123456 s'il n'a pas d'écran)")
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        return try {
            if (!device.createBond()) return device.bondState == BluetoothDevice.BOND_BONDED
            withTimeoutOrNull(90_000) { done.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    override suspend fun open(onStep: (String) -> Unit): String? {
        if (!bond(onStep)) return "Appairage refusé ou annulé. Vérifiez le code PIN du nœud."
        onStep("Connexion Bluetooth…")
        connected = CompletableDeferred()
        gatt = withContext(Dispatchers.Main) { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }
        if (withTimeoutOrNull(20_000) { connected.await() } != true) { close(); return "Le nœud ne répond pas en Bluetooth" }
        op<Int> { it.requestMtu(512) }
        onStep("Recherche du service Meshtastic…")
        if (op<Boolean>(15_000) { it.discoverServices() } != true) { close(); return "Services Bluetooth introuvables" }
        val service = gatt?.getService(SERVICE) ?: run { close(); return "Cet appareil n'est pas un nœud Meshtastic" }
        toRadio = service.getCharacteristic(TORADIO)
        fromRadio = service.getCharacteristic(FROMRADIO)
        val fromNum = service.getCharacteristic(FROMNUM)
        if (toRadio == null || fromRadio == null || fromNum == null) { close(); return "Firmware Meshtastic trop ancien" }
        gatt?.setCharacteristicNotification(fromNum, true)
        val cccd = fromNum.getDescriptor(CCCD)
        if (cccd != null) {
            op<Boolean> { g ->
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                else @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
        }
        return null
    }

    override suspend fun send(toRadio: ByteArray): Boolean {
        val c = this.toRadio ?: return false
        val ok = op<Boolean> { g ->
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, toRadio, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = toRadio; g.writeCharacteristic(c) }
        } == true
        drain()
        return ok
    }

    /** Reads FromRadio until the node has nothing more queued. */
    private fun drain() {
        scope.launch { drainLock.withLock { readAll() } }
    }

    private suspend fun readAll() {
        val c = fromRadio ?: return
        repeat(500) {
            val v = op<ByteArray> { g -> @Suppress("DEPRECATION") g.readCharacteristic(c) } ?: return
            if (v.isEmpty()) return
            onFromRadio(v)
        }
    }

    override fun close() {
        closed = true
        pending?.complete(null)
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** The stream framing used over TCP and serial: 0x94 0xC3, 16-bit big-endian length, protobuf. */
object MeshStreamFraming {
    const val START1 = 0x94
    const val START2 = 0xC3
    const val MAX_LEN = 512

    fun frame(payload: ByteArray): ByteArray =
        byteArrayOf(START1.toByte(), START2.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload

    /** Splits a byte stream into frames; bytes outside frames (the node's debug log) are skipped. */
    class Parser(private val onFrame: (ByteArray) -> Unit) {
        private var state = 0
        private var len = 0
        private var buf = ByteArray(0)
        private var got = 0

        fun feed(b: ByteArray, n: Int) {
            for (i in 0 until n) {
                val x = b[i].toInt() and 0xFF
                when (state) {
                    0 -> if (x == START1) state = 1
                    1 -> state = if (x == START2) 2 else if (x == START1) 1 else 0
                    2 -> { len = x shl 8; state = 3 }
                    3 -> {
                        len = len or x
                        if (len == 0 || len > MAX_LEN) state = 0 else { buf = ByteArray(len); got = 0; state = 4 }
                    }
                    4 -> {
                        buf[got++] = x.toByte()
                        if (got == len) { onFrame(buf); state = 0 }
                    }
                }
            }
        }
    }
}

/** Wi-Fi or Ethernet nodes: the same API on TCP port 4403. */
class TcpMeshTransport(
    private val host: String,
    private val port: Int = 4403,
    private val scope: CoroutineScope,
    private val onFromRadio: (ByteArray) -> Unit,
    private val onLost: (String) -> Unit,
) : MeshTransport {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    @Volatile private var closed = false
    private val writeLock = Mutex()

    override suspend fun open(onStep: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        onStep("Connexion à $host…")
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 8000)
            s.tcpNoDelay = true
        } catch (e: Exception) {
            runCatching { s.close() }
            return@withContext "Connexion impossible à $host:$port (${e.message ?: "pas de réponse"})"
        }
        socket = s
        out = s.getOutputStream()
        val input: InputStream = s.getInputStream()
        scope.launch(Dispatchers.IO) {
            val parser = MeshStreamFraming.Parser(onFromRadio)
            val b = ByteArray(1024)
            try {
                while (!closed) {
                    val n = input.read(b)
                    if (n < 0) break
                    parser.feed(b, n)
                }
            } catch (_: Exception) {
            }
            if (!closed) onLost("Connexion au nœud perdue")
        }
        null
    }

    override suspend fun send(toRadio: ByteArray): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                out?.apply { write(MeshStreamFraming.frame(toRadio)); flush() } != null
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
    }
}
