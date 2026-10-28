package be.maikel.xplorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Money

fun LazyListScope.marketsScreen(a: Analysis?, onAsset: (String) -> Unit) {
    if (a == null) { item { Muted("Chargement…") }; return }
    val st = a.snap.settings
    val owned = a.ledger.positions.filter { it.value > 1 }.map { it.symbol }
    val mine = (st.targets.keys + owned).toSet().sortedByDescending { st.targets[it] ?: 0.0 }
    val watch = st.watchlist.filter { it !in mine }
    item { SectionTitle("Mes cryptos") }
    item { Glass(padding = 6) { mine.forEach { MarketRow(a, it, onAsset) } } }
    if (watch.isNotEmpty()) {
        item { SectionTitle("Liste de surveillance") }
        item { Glass(padding = 6) { watch.forEach { MarketRow(a, it, onAsset) } } }
    }
    item {
        SectionTitle("Radar d'opportunités")
        val sig = a.signals.sortedBy { if (it.symbol in mine) 0 else 1 }
        Glass {
            if (sig.isEmpty()) Muted("Rien d'inhabituel détecté aujourd'hui.")
            sig.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                    CoinBadge(s.symbol, 30); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.kind, style = MaterialTheme.typography.labelLarge, color = Xp.Ice, modifier = Modifier.weight(1f))
                            Tag(if (s.positive) "favorable" else "prudence", if (s.positive) Xp.Gain else Xp.Amber)
                        }
                        Muted(s.text)
                    }
                }
            }
            Muted("Une détection n'est pas une recommandation : elle signale un fait de marché, à croiser avec le score.")
        }
    }
}

@Composable
private fun MarketRow(a: Analysis, sym: String, onAsset: (String) -> Unit) {
    val r = a.recs[sym]
    val p = a.snap.prices[sym]
    val ch = a.snap.change24[sym]
    Row(
        Modifier.fillMaxWidth().clickable { onAsset(sym) }.padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoinBadge(sym, 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(sym, style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
            Text(
                (p?.let { Money.price(it) } ?: "—") + (ch?.let { "  " + Money.pct(it, true) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = if ((ch ?: 0.0) >= 0) Xp.Gain else Xp.Loss,
            )
        }
        if (r != null) Column(horizontalAlignment = Alignment.End) {
            ActionBadge(r.action)
            if (r.score > 0) Muted("score ${r.score}/100")
        }
    }
}
