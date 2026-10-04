package com.allnetworktools.ui.pages.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.mesh.MeshConn
import com.allnetworktools.data.mesh.MeshNode
import com.allnetworktools.data.mesh.MeshProto
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Wall-clock seconds, refreshed every [periodMs]: node times come from the node in Unix time. */
@Composable
internal fun rememberEpochS(periodMs: Long = 5000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(periodMs) {
        while (true) { now = System.currentTimeMillis() / 1000; delay(periodMs) }
    }
    return now
}

/** Each node gets a stable colour from its number, like the official app's badges. */
internal fun nodeColor(num: Long): Color {
    val hue = ((num * 2654435761L) ushr 8) % 360
    return Color.hsv(hue.toFloat(), 0.55f, 0.78f)
}

internal fun onNodeColor(c: Color): Color = if (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue > 0.6) Color(0xFF1B1B1F) else Color.White

/** The short-name badge of a node. */
@Composable
internal fun NodeBadge(n: MeshNode, size: Dp = 44.dp) {
    val bg = nodeColor(n.num)
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(bg), contentAlignment = Alignment.Center) {
        Text(n.shortName.take(4), style = rf(if (size < 40.dp) 11 else 13, 16, 700), color = onNodeColor(bg), maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ChannelBadge(index: Int, size: Dp = 44.dp) {
    val acc = AntTheme.accent
    Box(Modifier.size(size).clip(CircleShape).background(acc.accent), contentAlignment = Alignment.Center) {
        Text("#$index", style = rf(13, 16, 700), color = acc.onAccent)
    }
}

internal fun ago(nowS: Long, s: Long): String {
    if (s <= 0) return "jamais entendu"
    val d = (nowS - s).coerceAtLeast(0)
    return when {
        d < 60 -> "à l'instant"
        d < 3600 -> "il y a ${d / 60} min"
        d < 86_400 -> "il y a ${d / 3600} h"
        else -> "il y a ${d / 86_400} j"
    }
}

/** Distance in metres between two points (haversine). */
internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

internal fun distanceText(m: Double): String = if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(Locale.FRANCE, m / 1000)

/** Display name of a channel: its own name, or the preset's for an unnamed primary channel. */
internal fun channelName(c: MeshProto.ChannelRecord, preset: Int): String = c.name.ifBlank { if (c.role == 1) MeshProto.presetChannelName(preset) else "Canal ${c.index}" }

/** Shown on pages that need a node when none is connected. */
@Composable
internal fun NotConnectedCard(vm: MainViewModel, conn: MeshConn) {
    val acc = AntTheme.accent
    SectionCard(color = acc.container) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(if (conn is MeshConn.Connecting) Sym.Sync else Sym.LinkOff, size = 26.dp, tint = acc.onContainer)
            Column(Modifier.weight(1f)) {
                Text(
                    when (conn) {
                        is MeshConn.Connecting -> "Connexion à ${conn.link.name}…"
                        is MeshConn.Failed -> "Nœud déconnecté"
                        else -> "Aucun nœud connecté"
                    },
                    style = rf(15, 20, 600), color = acc.onContainer,
                )
                Text(
                    when (conn) {
                        is MeshConn.Connecting -> conn.step
                        is MeshConn.Failed -> conn.message
                        else -> "Connectez votre nœud Meshtastic en Bluetooth ou en Wi-Fi pour envoyer et recevoir."
                    },
                    style = rf(13, 18), color = acc.onContainer,
                )
            }
        }
        if (conn !is MeshConn.Connecting) {
            PillButton("Connecter", { vm.navigate { it.copy(page = Page.ToolPage(Tool.MeshConnect)) } }, Modifier.padding(top = 12.dp).fillMaxWidth(), icon = Sym.Link, height = 44.dp)
        }
    }
}
