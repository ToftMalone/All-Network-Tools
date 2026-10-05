package com.allnetworktools.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.allnetworktools.BuildConfig
import com.allnetworktools.MainViewModel
import com.allnetworktools.ui.changelogItems
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.Spinner
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.update.ReleaseNote
import com.allnetworktools.update.isNewerVersion
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface ChangelogUi {
    data object Loading : ChangelogUi
    data class Loaded(val releases: List<ReleaseNote>) : ChangelogUi
    data class Failed(val message: String) : ChangelogUi
}

private val DateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

internal fun releaseDate(iso: String): String? = runCatching { OffsetDateTime.parse(iso).format(DateFmt) }.getOrNull()

/** Every published version with its notes, exactly as written in the GitHub releases. */
@Composable
fun ChangelogScreen(vm: MainViewModel) {
    var attempt by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<ChangelogUi>(ChangelogUi.Loading) }
    LaunchedEffect(attempt) {
        ui = ChangelogUi.Loading
        ui = runCatching { vm.updater.releases() }.fold(
            { ChangelogUi.Loaded(it) },
            { ChangelogUi.Failed(it.message ?: "réseau indisponible") },
        )
    }
    TopBarAction(Sym.Refresh) { attempt++ }
    PageColumn {
        when (val u = ui) {
            ChangelogUi.Loading -> Row(
                Modifier.padding(vertical = 48.dp).align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spinner()
                Text("Lecture des versions sur GitHub…", style = rf(14, 20), color = cs.onSurfaceVariant)
            }
            is ChangelogUi.Failed -> ToolError(Sym.CloudOff, "Journal indisponible", "Impossible de joindre GitHub (${u.message}).", "Réessayer") { attempt++ }
            is ChangelogUi.Loaded -> if (u.releases.isEmpty()) {
                ToolError(Sym.NewspaperNotes, "Aucune version publiée", "Les notes apparaîtront ici dès la première release.", "Actualiser") { attempt++ }
            } else {
                u.releases.forEachIndexed { i, r -> ReleaseCard(r, Modifier.rise(i)) }
            }
        }
    }
}

@Composable
private fun ReleaseCard(r: ReleaseNote, modifier: Modifier) {
    val installed = !isNewerVersion(r.tag, BuildConfig.VERSION_NAME) && !isNewerVersion(BuildConfig.VERSION_NAME, r.tag)
    val newer = isNewerVersion(r.tag, BuildConfig.VERSION_NAME)
    val items = changelogItems(r.notes)
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Version ${r.tag.removePrefix("v")}", style = gs(20, 26, 500))
                releaseDate(r.publishedAt)?.let { Text(it, style = rf(13, 18), color = cs.onSurfaceVariant) }
            }
            when {
                installed -> TechChip("Installée", cs.primary, cs.onPrimary)
                newer -> TechChip("Nouvelle", AntTheme.net.good, cs.surface)
            }
        }
        if (items.isEmpty()) {
            Text("Pas de notes pour cette version.", Modifier.padding(top = 10.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
        } else {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("•", style = rf(14, 20, 700), color = cs.primary)
                        Text(line, Modifier.weight(1f), style = rf(14, 20))
                    }
                }
            }
        }
    }
}
