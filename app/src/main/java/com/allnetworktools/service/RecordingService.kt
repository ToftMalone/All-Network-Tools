package com.allnetworktools.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.allnetworktools.AntApplication
import com.allnetworktools.MainActivity
import com.allnetworktools.R
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecKind(val label: String) { CellLog("Journal des cellules"), Signal("Historique du signal"), Nmea("Journal NMEA") }

/**
 * Which recordings the user started. They run in [RecordingService] and keep going with the app
 * closed until stopped from the app or the notification. State survives process restarts.
 */
class RecordingController(private val context: Context) {
    private val prefs = context.getSharedPreferences("recording", Context.MODE_PRIVATE)
    private val _active = MutableStateFlow(
        prefs.getStringSet("active", emptySet()).orEmpty().mapNotNull { runCatching { RecKind.valueOf(it) }.getOrNull() }.toSet(),
    )
    val active: StateFlow<Set<RecKind>> = _active.asStateFlow()

    private val _nmeaFile = MutableStateFlow(prefs.getString("nmea_file", null)?.let(::File)?.takeIf { it.exists() })
    val nmeaFile: StateFlow<File?> = _nmeaFile.asStateFlow()
    private val _nmeaCount = MutableStateFlow(0)
    val nmeaCount: StateFlow<Int> = _nmeaCount.asStateFlow()

    fun since(kind: RecKind): Long? = prefs.getLong("since_${kind.name}", 0L).takeIf { it > 0 && kind in _active.value }

    private fun save() = prefs.edit().putStringSet("active", _active.value.map { it.name }.toSet()).apply()

    fun start(kind: RecKind) {
        if (kind in _active.value) return
        prefs.edit().putLong("since_${kind.name}", System.currentTimeMillis()).apply()
        _active.value = _active.value + kind
        save()
        RecordingService.start(context)
    }

    fun stop(kind: RecKind) {
        _active.value = _active.value - kind
        save()
    }

    fun stopAll() {
        _active.value = emptySet()
        save()
    }

    /** A new file per NMEA recording, in app storage (shared through the FileProvider). */
    internal fun newNmeaFile(): File {
        val dir = File(context.filesDir, "nmea").apply { mkdirs() }
        val f = File(dir, "gnss_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.FRANCE).format(Date())}.nmea")
        _nmeaFile.value = f
        _nmeaCount.value = 0
        prefs.edit().putString("nmea_file", f.path).apply()
        return f
    }

    internal fun countNmea() {
        _nmeaCount.value++
    }
}

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as AntApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.recording.stopAll()
            return START_NOT_STICKY
        }
        val active = app.recording.active.value
        if (active.isEmpty()) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(active), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Android refuses a location service started from the background (e.g. a restart after
            // the process was killed): drop the recordings rather than pretend they run.
            app.recording.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            run()
        }
        return START_STICKY
    }

    private var started = false

    private fun run() {
        val rec = app.recording
        scope.launch {
            rec.active.collect { kinds ->
                if (kinds.isEmpty()) {
                    ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(kinds))
                }
            }
        }
        scope.launch {
            app.history.load(app.settings.settings.first().historyDays)
            rec.active.map { (RecKind.CellLog in it) to (RecKind.Signal in it) }.distinctUntilChanged().collectLatest { (log, signal) ->
                if (log || signal) app.cell.cells(1000).collect { app.cellRecorder.onState(it, log, signal) }
                else app.cellRecorder.reset()
            }
        }
        scope.launch {
            rec.active.map { RecKind.Nmea in it }.distinctUntilChanged().collectLatest { on ->
                if (!on) return@collectLatest
                val file = rec.newNmeaFile()
                val writer: Writer = withContext(Dispatchers.IO) { file.bufferedWriter() }
                try {
                    app.gnss.nmea().collect { (_, sentence) ->
                        withContext(Dispatchers.IO) { writer.write(sentence); writer.write("\r\n") }
                        rec.countNmea()
                    }
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { writer.close() } }
                }
            }
        }
    }

    private fun notification(kinds: Set<RecKind>): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Enregistrements", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Journal des cellules, historique du signal et journal NMEA en arrière-plan"
                },
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(if (kinds.size == 1) "${kinds.first().label} en cours" else "${kinds.size} enregistrements en cours")
            .setContentText(kinds.joinToString(" · ") { it.label })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Tout arrêter", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.allnetworktools.STOP_RECORDING"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)) }
        }
    }
}
