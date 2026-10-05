package com.allnetworktools.ui.pages.talkie

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.radio.RadioCable
import com.allnetworktools.data.radio.RadioCableRepository
import com.allnetworktools.data.radio.RadioChannel
import com.allnetworktools.data.radio.RadioException
import com.allnetworktools.data.radio.RadioPreset
import com.allnetworktools.data.radio.RadioSpec
import com.allnetworktools.data.radio.RadioSpecs
import com.allnetworktools.data.radio.SerialLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProgPhase(val label: String) {
    Idle(""),
    Reading("Lecture du talkie…"),
    Writing("Écriture dans le talkie…"),
    Verifying("Vérification…"),
}

class ProgMessage(val text: String, val kind: Kind) {
    enum class Kind { Info, Success, Error }
}

/** What happened when a preset was added to the list. */
class PresetResult(val added: Int, val outOfBand: Int, val noRoom: Int)

/**
 * Reads the channel memories of a handheld through its programming cable, lets the user edit them, and writes them back.
 * The list lives in memory only. Writing downloads the radio again first, changes nothing but the edited memories on that
 * fresh copy, reads back to check, and keeps the previous state for one-tap restoring.
 */
class ChannelsController(
    private val cables: RadioCableRepository,
    private val scope: CoroutineScope,
    val specs: List<RadioSpec> = RadioSpecs.all(),
) {
    var spec by mutableStateOf(specs.first())
        private set
    val channels = mutableStateListOf<RadioChannel?>().apply { repeat(specs.first().slots) { add(null) } }
    var phase by mutableStateOf(ProgPhase.Idle)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var message by mutableStateOf<ProgMessage?>(null)
        private set

    /** Identification string the radio answered with, for the support-minded. */
    var ident by mutableStateOf<String?>(null)
        private set
    var canRestore by mutableStateOf(false)
        private set

    /** What the radio held when last read or written: the reference for counting edits. */
    private var baseline: List<RadioChannel?>? = null
    private var backup: ByteArray? = null
    private var backupSlots: List<Int> = emptyList()
    private var backupSpec: RadioSpec? = null

    val count: Int get() = channels.count { it != null }

    val changed: Int
        get() {
            val b = baseline ?: return count
            return channels.indices.count { channels[it] != b.getOrNull(it) }
        }

    val busy: Boolean get() = phase != ProgPhase.Idle

    fun firstFree(): Int? = channels.indexOfFirst { it == null }.takeIf { it >= 0 }

    fun select(s: RadioSpec) {
        if (busy || s.id == spec.id) return
        // Memories carry over to the other model so a list can be copied from one radio to the other.
        val old = channels.toList()
        spec = s
        channels.clear()
        var dropped = 0
        for (i in 0 until s.slots) channels.add(old.getOrNull(i)?.let { s.normalize(it) }?.takeIf { s.inBand(it.rxHz) })
        old.forEachIndexed { i, c -> if (c != null && channels.getOrNull(i) == null) dropped++ }
        baseline = null
        ident = null
        message = if (old.any { it != null }) {
            ProgMessage(
                "Liste conservée pour le ${s.label}" + if (dropped > 0) " ($dropped canaux hors de ses bandes ou de sa capacité ont été retirés)." else ".",
                ProgMessage.Kind.Info,
            )
        } else null
    }

    fun edit(ch: RadioChannel) {
        channels[ch.slot] = spec.normalize(ch)
    }

    fun clear(slot: Int) {
        channels[slot] = null
    }

    fun clearAll() {
        for (i in channels.indices) channels[i] = null
    }

    fun insertPreset(preset: RadioPreset, listenOnly: Boolean): PresetResult {
        val usable = preset.channels.filter { spec.inBand(it.rxHz) }
        val free = channels.indices.filter { channels[it] == null }
        val fit = usable.take(free.size)
        val sub = preset.copy(channels = fit)
        sub.toChannels(free, listenOnly).forEach { channels[it.slot] = spec.normalize(it) }
        return PresetResult(fit.size, preset.channels.size - usable.size, usable.size - fit.size)
    }

    // ---- cable ----------------------------------------------------------------------------------

    private suspend fun <T> withLink(cable: RadioCable, block: (SerialLink) -> T): T {
        if (!cables.hasPermission(cable) && !cables.requestPermission(cable)) throw RadioException("Accès USB au câble refusé.")
        return withContext(Dispatchers.IO) {
            val link = cables.open(cable, spec.baud) ?: throw RadioException("Impossible d'ouvrir le câble USB.")
            link.use(block)
        }
    }

    private fun fail(e: Exception) {
        message = ProgMessage(if (e is RadioException) e.message ?: "Erreur de communication." else "Erreur : ${e.message ?: e.javaClass.simpleName}", ProgMessage.Kind.Error)
    }

    fun read(cable: RadioCable) {
        if (busy) return
        scope.launch {
            phase = ProgPhase.Reading; progress = 0f; message = null
            try {
                val d = withLink(cable) { spec.download(it) { p -> progress = p } }
                val list = (0 until spec.slots).map { spec.decode(d.image, it) }
                channels.clear(); channels.addAll(list)
                baseline = list
                ident = d.ident
                canRestore = false
                message = ProgMessage(
                    "${list.count { it != null }} canaux lus dans le ${spec.label}." +
                        if (d.known) "" else " Firmware non reconnu : lecture seule, l'écriture sera refusée.",
                    if (d.known) ProgMessage.Kind.Success else ProgMessage.Kind.Info,
                )
            } catch (e: Exception) {
                fail(e)
            } finally {
                phase = ProgPhase.Idle
            }
        }
    }

    fun write(cable: RadioCable) {
        if (busy) return
        scope.launch {
            phase = ProgPhase.Writing; progress = 0f; message = null
            try {
                val wanted = channels.toList()
                // Two separate sessions, each opened by the radio's own handshake, as the manufacturer's software does.
                val d = withLink(cable) { link -> spec.download(link) { progress = it * 0.3f } }
                val before = d.image.copyOf()
                val image = d.image
                val slots = (0 until spec.slots).filter { spec.decode(image, it) != wanted[it] }
                if (slots.isNotEmpty()) {
                    for (s in slots) wanted[s]?.let { spec.encode(image, it) } ?: spec.erase(image, s)
                    delay(1500)
                    withLink(cable) { link -> spec.upload(link, image, slots) { progress = 0.3f + it * 0.6f } }
                }
                if (slots.isEmpty()) {
                    message = ProgMessage("Le talkie contient déjà exactement ces canaux : rien à écrire.", ProgMessage.Kind.Info)
                    return@launch
                }
                backup = before; backupSlots = slots; backupSpec = spec; canRestore = true
                phase = ProgPhase.Verifying
                progress = 0.9f
                message = verify(cable, wanted, slots)
                baseline = wanted
            } catch (e: Exception) {
                fail(e)
            } finally {
                phase = ProgPhase.Idle
            }
        }
    }

    /** Reads the radio again and compares what was written. The radio may need a moment, or a power cycle, to leave clone mode. */
    private suspend fun verify(cable: RadioCable, wanted: List<RadioChannel?>, slots: List<Int>): ProgMessage {
        repeat(2) { attempt ->
            delay(if (attempt == 0) 1500 else 3000)
            try {
                val d = withLink(cable) { spec.download(it) { p -> progress = 0.9f + p * 0.1f } }
                val bad = slots.count { spec.decode(d.image, it) != wanted[it] }
                return if (bad == 0) ProgMessage("${slots.size} canaux écrits et vérifiés par relecture.", ProgMessage.Kind.Success)
                else ProgMessage("$bad canaux sur ${slots.size} relus différemment : relisez le talkie pour voir ce qu'il contient.", ProgMessage.Kind.Error)
            } catch (_: Exception) {
                // Try once more.
            }
        }
        return ProgMessage(
            "${slots.size} canaux écrits. Vérification impossible : éteignez puis rallumez le talkie avant de le relire.",
            ProgMessage.Kind.Info,
        )
    }

    /** Puts the radio back as it was before the last write. */
    fun restore(cable: RadioCable) {
        val image = backup ?: return
        val s = backupSpec ?: return
        if (busy || s.id != spec.id) return
        scope.launch {
            phase = ProgPhase.Writing; progress = 0f; message = null
            try {
                withLink(cable) { link -> s.upload(link, image, backupSlots) { progress = it } }
                val restored = (0 until s.slots).map { s.decode(image, it) }
                channels.clear(); channels.addAll(restored)
                baseline = restored
                canRestore = false
                message = ProgMessage("État précédent du talkie rétabli (${backupSlots.size} canaux).", ProgMessage.Kind.Success)
            } catch (e: Exception) {
                fail(e)
            } finally {
                phase = ProgPhase.Idle
            }
        }
    }

    /** Sample list for screenshots and tests. */
    internal fun setForTest(list: List<RadioChannel>, msg: ProgMessage? = null) {
        channels.clear()
        for (i in 0 until spec.slots) channels.add(list.firstOrNull { it.slot == i })
        baseline = channels.toList()
        ident = "50 BB FF 20 12 07 25 DD"
        message = msg
    }
}
