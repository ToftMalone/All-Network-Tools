package com.allnetworktools.ui.pages.talkie

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.radio.RadioCable
import com.allnetworktools.data.radio.RadioCableRepository
import com.allnetworktools.data.radio.RadioChannel
import com.allnetworktools.data.radio.GenericRadio
import com.allnetworktools.data.radio.RadioException
import com.allnetworktools.data.radio.RadioLimits
import com.allnetworktools.data.radio.RadioNoAnswer
import com.allnetworktools.data.radio.RadioPreset
import com.allnetworktools.data.radio.RadioSpec
import com.allnetworktools.data.radio.RadioSpecs
import com.allnetworktools.data.radio.SerialLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.util.plural

enum class ProgPhase(val label: String) {
    Idle(""),
    Detecting("Recherche du talkie…"),
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
    /** The model that answered the last time, null until a radio has been recognised. */
    var detected by mutableStateOf<RadioSpec?>(null)
        private set

    /** What the list has to respect: the recognised radio's limits, or generous ones before that. */
    val limits: RadioLimits get() = detected ?: GenericRadio

    val channels = mutableStateListOf<RadioChannel?>().apply { repeat(GenericRadio.slots) { add(null) } }
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

    fun edit(ch: RadioChannel) {
        channels[ch.slot] = limits.normalize(ch)
    }

    fun clear(slot: Int) {
        channels[slot] = null
    }

    fun clearAll() {
        for (i in channels.indices) channels[i] = null
    }

    fun insertPreset(preset: RadioPreset, listenOnly: Boolean): PresetResult {
        val usable = preset.channels.filter { limits.inBand(it.rxHz) }
        val free = channels.indices.filter { channels[it] == null }
        val fit = usable.take(free.size)
        val sub = preset.copy(channels = fit)
        sub.toChannels(free, listenOnly).forEach { channels[it.slot] = limits.normalize(it) }
        return PresetResult(fit.size, preset.channels.size - usable.size, usable.size - fit.size)
    }

    /** The list as the recognised radio will hold it: its names, power levels and bands, its number of memories. */
    private class Fitted(val list: List<RadioChannel?>, val outOfBand: Int, val overflow: Int)

    private fun fit(s: RadioSpec, source: List<RadioChannel?>): Fitted {
        var outOfBand = 0
        var overflow = 0
        val out = MutableList<RadioChannel?>(s.slots) { null }
        source.forEachIndexed { i, c ->
            if (c == null) return@forEachIndexed
            when {
                i >= s.slots -> overflow++
                !s.inBand(c.rxHz) -> outOfBand++
                else -> out[i] = s.normalize(c)
            }
        }
        return Fitted(out, outOfBand, overflow)
    }

    // ---- cable ----------------------------------------------------------------------------------

    private suspend fun <T> withLink(cable: RadioCable, spec: RadioSpec, block: (SerialLink) -> T): T {
        if (!cables.hasPermission(cable) && !cables.requestPermission(cable)) throw RadioException("Accès USB au câble refusé.")
        return withContext(Dispatchers.IO) {
            val link = cables.open(cable, spec.baud) ?: throw RadioException("Impossible d'ouvrir le câble USB.")
            link.use(block)
        }
    }

    /** Tries each known model's handshake, the one that answered last time first, and runs [block] with the one that answers. */
    private suspend fun <T> withRadio(cable: RadioCable, block: (RadioSpec, SerialLink) -> T): T {
        val order = listOfNotNull(detected) + specs.filter { it !== detected }
        for (s in order) {
            try {
                val r = withLink(cable, s) { link -> block(s, link) }
                detected = s
                return r
            } catch (_: RadioNoAnswer) {
                // Not this model.
            }
        }
        throw RadioException(
            "Aucun talkie reconnu (" + specs.joinToString(" ou ") { it.label } + "). Vérifiez que le câble est enfoncé à fond dans la prise du talkie, " +
                "que celui-ci est allumé et que son volume n'est pas à zéro.",
        )
    }

    private fun fail(e: Exception) {
        message = ProgMessage(if (e is RadioException) e.message ?: "Erreur de communication." else "Erreur : ${e.message ?: e.javaClass.simpleName}", ProgMessage.Kind.Error)
    }

    private fun onFirstBlock(p: Float): Float {
        if (phase == ProgPhase.Detecting) phase = ProgPhase.Reading
        return p
    }

    fun read(cable: RadioCable) {
        if (busy) return
        scope.launch {
            phase = ProgPhase.Detecting; progress = 0f; message = null
            try {
                val d = withRadio(cable) { s, link -> s to s.download(link) { p -> progress = onFirstBlock(p) } }
                val s = d.first
                val list = (0 until s.slots).map { s.decode(d.second.image, it) }
                channels.clear(); channels.addAll(list)
                baseline = list
                ident = d.second.ident
                canRestore = false
                message = ProgMessage(
                    "${s.label} reconnu : ${list.count { it != null }} canaux lus." +
                        if (d.second.known) "" else " Firmware non reconnu : lecture seule, l'écriture sera refusée.",
                    if (d.second.known) ProgMessage.Kind.Success else ProgMessage.Kind.Info,
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
            phase = ProgPhase.Detecting; progress = 0f; message = null
            try {
                // Two separate sessions, each opened by the radio's own handshake, as the manufacturer's software does.
                val (s, d) = withRadio(cable) { s, link -> s to s.download(link) { progress = onFirstBlock(it) * 0.3f } }
                val fitted = fit(s, channels.toList())
                val wanted = fitted.list
                val before = d.image.copyOf()
                val image = d.image
                val slots = (0 until s.slots).filter { s.decode(image, it) != wanted[it] }
                // The list now shows what this radio will hold.
                channels.clear(); channels.addAll(wanted)
                val notes = buildList {
                    if (fitted.outOfBand > 0) add("${fitted.outOfBand} ${plural(fitted.outOfBand, "canal hors des bandes", "canaux hors des bandes")} du ${s.label} non écrit")
                    if (fitted.overflow > 0) add("${fitted.overflow} ${plural(fitted.overflow, "canal", "canaux")} en trop pour ses ${s.slots} mémoires non écrit")
                }.joinToString(" · ").let { if (it.isEmpty()) "" else " ($it)" }
                if (slots.isEmpty()) {
                    baseline = wanted
                    message = ProgMessage("Le ${s.label} contient déjà exactement ces canaux : rien à écrire.$notes", ProgMessage.Kind.Info)
                    return@launch
                }
                phase = ProgPhase.Writing
                for (slot in slots) wanted[slot]?.let { s.encode(image, it) } ?: s.erase(image, slot)
                delay(1500)
                withLink(cable, s) { link -> s.upload(link, image, slots) { progress = 0.3f + it * 0.6f } }
                backup = before; backupSlots = slots; backupSpec = s; canRestore = true
                phase = ProgPhase.Verifying
                progress = 0.9f
                val v = verify(cable, s, wanted, slots)
                message = ProgMessage(v.text + notes, v.kind)
                baseline = wanted
            } catch (e: Exception) {
                fail(e)
            } finally {
                phase = ProgPhase.Idle
            }
        }
    }

    /** Reads the radio again and compares what was written. The radio may need a moment, or a power cycle, to leave clone mode. */
    private suspend fun verify(cable: RadioCable, s: RadioSpec, wanted: List<RadioChannel?>, slots: List<Int>): ProgMessage {
        repeat(2) { attempt ->
            delay(if (attempt == 0) 1500 else 3000)
            try {
                val d = withLink(cable, s) { s.download(it) { p -> progress = 0.9f + p * 0.1f } }
                val bad = slots.count { s.decode(d.image, it) != wanted[it] }
                return if (bad == 0) ProgMessage("${slots.size} canaux écrits dans le ${s.label} et vérifiés par relecture.", ProgMessage.Kind.Success)
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
        if (busy) return
        scope.launch {
            phase = ProgPhase.Writing; progress = 0f; message = null
            try {
                withLink(cable, s) { link -> s.upload(link, image, backupSlots) { progress = it } }
                val restored = (0 until s.slots).map { s.decode(image, it) }
                channels.clear(); channels.addAll(restored)
                baseline = restored
                canRestore = false
                message = ProgMessage("État précédent du ${s.label} rétabli (${backupSlots.size} canaux).", ProgMessage.Kind.Success)
            } catch (e: Exception) {
                fail(e)
            } finally {
                phase = ProgPhase.Idle
            }
        }
    }

    /** Sample list for screenshots and tests. */
    internal fun setForTest(list: List<RadioChannel>, msg: ProgMessage? = null, model: RadioSpec? = null) {
        detected = model
        channels.clear()
        for (i in 0 until limits.slots) channels.add(list.firstOrNull { it.slot == i })
        baseline = channels.toList()
        ident = model?.let { "50 BB FF 20 12 07 25 DD" }
        message = msg
    }
}
