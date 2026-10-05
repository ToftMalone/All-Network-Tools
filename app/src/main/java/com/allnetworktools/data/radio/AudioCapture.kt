package com.allnetworktools.data.radio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/** A place the radio's audio can come from: the phone's microphone held to the speaker, or a USB / wired audio interface. */
data class AudioInput(val id: Int, val label: String, val usb: Boolean, internal val info: AudioDeviceInfo? = null)

/** Records the receiver's audio, unprocessed (no echo cancelling, gain control or noise suppression, which would ruin the tones). */
open class AudioCapture(private val context: Context) {
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null

    open fun inputs(): List<AudioInput> {
        val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { d ->
            when (d.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET ->
                    AudioInput(d.id, "Interface USB" + d.productName.toString().takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), true, d)
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioInput(d.id, "Prise casque", false, d)
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInput(d.id, "Micro du téléphone", false, d)
                else -> null
            }
        }.sortedByDescending { it.usb }
    }

    /** Starts recording; [onSamples] gets mono floats in [-1, 1] on a dedicated thread. Returns an error message, or null when running. */
    open fun start(input: AudioInput?, rate: Int, onSamples: (FloatArray, Int) -> Unit, onError: (String) -> Unit): String? {
        if (record != null) return null
        val am = context.getSystemService(AudioManager::class.java)
        val source = if (am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val rec = try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
                )
                .setBufferSizeInBytes(maxOf(min, rate / 2 * 2))
                .build()
        } catch (e: SecurityException) {
            return "Autorisez le micro pour recevoir l'audio du talkie."
        } catch (e: Exception) {
            return "Impossible d'ouvrir l'entrée audio : ${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return "L'entrée audio n'a pas démarré." }
        input?.info?.let { rec.preferredDevice = it }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return "L'entrée audio n'a pas démarré : ${e.message}"
        }
        record = rec
        thread = Thread({
            val pcm = ShortArray(rate / 20)
            val out = FloatArray(pcm.size)
            try {
                while (record === rec) {
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n < 0) { onError("Lecture audio interrompue"); break }
                    for (i in 0 until n) out[i] = pcm[i] / 32768f
                    if (n > 0) onSamples(out, n)
                }
            } catch (e: Exception) {
                if (record === rec) onError(e.message ?: "Erreur audio")
            }
        }, "talkie-audio").apply { priority = Thread.MAX_PRIORITY; start() }
        return null
    }

    open fun stop() {
        val rec = record ?: return
        record = null
        runCatching { thread?.join(500) }
        runCatching { rec.stop() }
        runCatching { rec.release() }
        thread = null
    }
}
