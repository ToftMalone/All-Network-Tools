package com.allnetworktools.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val RadioTech.rank get() = when (this) {
    RadioTech.NR -> 4; RadioTech.LTE -> 3; RadioTech.WCDMA -> 2; RadioTech.GSM -> 1
}

private val RadioTech.generation get() = when (this) {
    RadioTech.NR -> "5G"; RadioTech.LTE -> "4G"; RadioTech.WCDMA -> "3G"; RadioTech.GSM -> "2G"
}

private val RadioTech.node get() = if (this == RadioTech.NR) "gNB" else "eNB"

/**
 * Turns successive serving-cell states into journal events (cell changes, technology changes,
 * loss and recovery of service) and records one signal sample per minute.
 * Runs only while the app is in the foreground and recording is not paused.
 */
class CellRecorder(private val history: HistoryStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _pausedAt = MutableStateFlow<Long?>(null)
    val pausedAt: StateFlow<Long?> = _pausedAt.asStateFlow()
    private var last: CellState? = null
    private var lastSampleAt = 0L

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        _pausedAt.value = if (on) null else clock()
        if (!on) last = null
    }

    /** Pure diff of two states, exposed for tests. */
    fun diff(prev: CellState, cur: CellState, at: Long): CellEvent? {
        val p = prev.serving
        val c = cur.serving
        return when {
            prev.hasService && !cur.hasService -> CellEvent(at, "lost", "Perte du service", p?.let { "Dernière cellule ${label(it)}" } ?: "", p?.level, "Aucune cellule de service", null)
            !prev.hasService && cur.hasService && c != null -> CellEvent(at, "back", "Service rétabli", "${c.tech.generation} · ${label(c)}", c.level, cur.operator.orEmpty(), c.tech.name)
            p == null || c == null || p.key == c.key -> null
            p.tech != c.tech -> {
                val up = c.tech.rank > p.tech.rank
                CellEvent(
                    at, if (up) "up" else "down", "${p.tech.generation} → ${c.tech.generation}",
                    transition(p, c), c.level,
                    if (up) "Retour de la couverture ${c.tech.short}" else "Perte de couverture ${p.tech.short}", c.tech.name,
                )
            }
            else -> CellEvent(
                at, "handover", "Changement de cellule ${c.tech.short}", transition(p, c), c.level,
                when {
                    c.nodeId != null && c.nodeId == p.nodeId -> "Même ${c.tech.node} ${c.nodeId}"
                    c.nodeId != null -> "${c.tech.node} ${c.nodeId}"
                    else -> ""
                },
                c.tech.name,
            )
        }
    }

    private fun label(m: CellMeasure) = listOfNotNull(m.band, m.pci?.let { "PCI $it" }).joinToString(" · ").ifEmpty { m.tech.short }

    private fun transition(p: CellMeasure, c: CellMeasure): String {
        val pci = "PCI ${p.pci ?: "?"} → ${c.pci ?: "?"}"
        val band = if (p.band != null && c.band != null) if (p.band == c.band) c.band else "${p.band} → ${c.band}" else null
        return listOfNotNull(pci, band).joinToString(" · ")
    }

    suspend fun onState(cur: CellState?) {
        if (!_enabled.value) return
        if (cur == null) {
            last = null; return
        }
        val now = clock()
        last?.let { prev -> diff(prev, cur, now)?.let { history.addCellEvent(it) } }
        last = cur
        val s = cur.serving
        if (s != null && now - lastSampleAt >= 60_000) {
            lastSampleAt = now
            history.addSignal(SignalSample(now, s.tech.name, s.rsrp, s.rsrq, s.sinr))
        }
    }
}
