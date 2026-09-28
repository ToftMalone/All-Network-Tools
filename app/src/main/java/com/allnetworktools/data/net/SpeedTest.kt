package com.allnetworktools.data.net

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class SpeedStage { Ping, Download, Upload, Done }

data class SpeedSample(
    val stage: SpeedStage,
    /** Current throughput (Mb/s) or ping (ms) while [stage] is Ping. */
    val value: Float,
    val pingMs: Float? = null,
    val jitterMs: Float? = null,
    val downMbps: Float? = null,
    val upMbps: Float? = null,
)

data class SpeedServer(val city: String?, val colo: String?, val isp: String?)

/** Throughput test against Cloudflare's public speed test endpoints. */
object SpeedTest {
    private const val BASE = "https://speed.cloudflare.com"
    private const val PHASE_MS = 8_000L
    private const val WARMUP_MS = 1_000L
    private const val STREAMS = 4

    suspend fun server(): SpeedServer = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("$BASE/meta").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            SpeedServer(j.optString("city").ifEmpty { null }, j.optString("colo").ifEmpty { null }, j.optString("asOrganization").ifEmpty { null })
        }.getOrDefault(SpeedServer(null, null, null))
    }

    private fun open(path: String, timeout: Int = 10_000) = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
        connectTimeout = timeout; readTimeout = timeout
        setRequestProperty("User-Agent", "AllNetworkTools")
    }

    fun run(): Flow<SpeedSample> = channelFlow {
        // Ping: time to first byte of empty downloads, first one discarded (TLS handshake).
        val pings = mutableListOf<Float>()
        withContext(Dispatchers.IO) {
            repeat(9) { i ->
                val t0 = System.nanoTime()
                val c = open("/__down?bytes=0")
                c.inputStream.use { it.read() }
                c.disconnect()
                val ms = (System.nanoTime() - t0) / 1e6f
                if (i > 0) {
                    pings += ms
                    send(SpeedSample(SpeedStage.Ping, ms))
                }
            }
        }
        val ping = pings.sorted()[pings.size / 2]
        val jitter = pings.zipWithNext { a, b -> abs(a - b) }.average().toFloat()

        val down = measure(SpeedStage.Download, ping, jitter, null) { counter, active ->
            while (active()) {
                val c = open("/__down?bytes=50000000")
                try {
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (active()) {
                            val n = input.read(buf)
                            if (n < 0) break
                            counter.addAndGet(n.toLong())
                        }
                    }
                } finally {
                    c.disconnect()
                }
            }
        }
        val up = measure(SpeedStage.Upload, ping, jitter, down) { counter, active ->
            val chunk = ByteArray(64 * 1024)
            while (active()) {
                val c = open("/__up")
                try {
                    c.requestMethod = "POST"; c.doOutput = true
                    val size = 25_000_000
                    c.setFixedLengthStreamingMode(size)
                    c.setRequestProperty("Content-Type", "application/octet-stream")
                    val out: OutputStream = c.outputStream
                    var sent = 0
                    while (sent < size && active()) {
                        val n = minOf(chunk.size, size - sent)
                        out.write(chunk, 0, n)
                        sent += n
                        counter.addAndGet(n.toLong())
                    }
                    if (sent == size) {
                        out.close()
                        c.inputStream.use { it.readBytes() }
                    }
                } catch (_: Exception) {
                    if (active()) throw java.io.IOException("upload interrupted")
                } finally {
                    c.disconnect()
                }
            }
        }
        send(SpeedSample(SpeedStage.Done, down, ping, jitter, down, up))
    }

    /** Runs [STREAMS] transfers for [PHASE_MS], emitting the throughput every 250 ms; returns the mean after warm-up. */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<SpeedSample>.measure(
        stage: SpeedStage,
        ping: Float,
        jitter: Float,
        down: Float?,
        transfer: (AtomicLong, () -> Boolean) -> Unit,
    ): Float = coroutineScope {
        val counter = AtomicLong()
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000L
        val active = { elapsed() < PHASE_MS && isActive }
        val jobs = List(STREAMS) { launch(Dispatchers.IO) { runCatching { transfer(counter, active) }.onFailure { if (elapsed() < PHASE_MS) throw it } } }
        var warmBytes = 0L
        var lastBytes = 0L
        var lastT = 0L
        while (elapsed() < PHASE_MS) {
            delay(250)
            val t = elapsed()
            val b = counter.get()
            if (t >= WARMUP_MS && warmBytes == 0L) warmBytes = b
            val mbps = (b - lastBytes) * 8f / ((t - lastT).coerceAtLeast(1) / 1000f) / 1e6f
            lastBytes = b; lastT = t
            send(SpeedSample(stage, mbps, ping, jitter, down))
        }
        jobs.forEach { it.cancel() }
        val bytes = counter.get() - warmBytes
        bytes * 8f / ((PHASE_MS - WARMUP_MS) / 1000f) / 1e6f
    }
}
