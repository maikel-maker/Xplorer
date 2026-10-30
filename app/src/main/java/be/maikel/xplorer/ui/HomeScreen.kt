package be.maikel.xplorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Money

/** Vue détaillée (ancien accueil), désormais dans l'onglet Détails. */
fun LazyListScope.overviewScreen(a: Analysis?, hasKey: Boolean, onAsset: (String) -> Unit, onTab: (Int) -> Unit) {
    if (a == null) { item { Muted("Chargement…") }; return }
    val l = a.ledger
    item {
        Glass(padding = 20) {
            Muted("Ton portefeuille")
            val parts = Money.eur(l.totalEur, 2).split(",")
            Text(
                buildAnnotatedString {
                    append(parts[0])
                    if (parts.size > 1) withStyle(SpanStyle(fontSize = 26.sp, color = Xp.Slate)) { append("," + parts[1]) }
                },
                style = MaterialTheme.typography.displayLarge, color = Xp.Ice,
            )
            Spacer(Modifier.height(6.dp))
            val (d, dPct) = a.day
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("${Money.signedEur(d)} (${Money.pct(dPct, true)}) en 24 h", if (d >= 0) Xp.Gain else Xp.Loss)
                Tag("Liquidités ${Money.eur(l.cashEur, 0)}", Xp.Slate)
            }
            if (!hasKey) {
                Spacer(Modifier.height(12.dp))
                Banner("Branche ta clé Coinbase en lecture seule (Réglages) pour voir ton vrai portefeuille.", error = false) { onTab(4) }
            } else {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val startTxt = a.snap.settings.startDate?.let { java.text.SimpleDateFormat("d MMM", java.util.Locale.FRANCE).format(java.util.Date(it)) }
                    Stat("Capital apporté", Money.eur(a.capital, 0), Modifier.weight(1f), sub = when {
                        a.capitalManual -> "corrigé à la main"
                        a.sinceStart -> "depuis le $startTxt"
                        else -> "tous les virements"
                    })
                    Stat("Gain ou perte", Money.signedEur(a.gain, 0), Modifier.weight(1f), if (a.gain >= 0) Xp.Gain else Xp.Loss, Money.pct(a.gainPct, true))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Latent", Money.signedEur(l.unrealized, 0), Modifier.weight(1f), if (l.unrealized >= 0) Xp.Gain else Xp.Loss)
                    Stat("Réalisé", Money.signedEur(l.realized, 0), Modifier.weight(1f), if (l.realized >= 0) Xp.Gain else Xp.Loss, "depuis toujours")
                    Stat("Frais", Money.eur(l.fees, 0), Modifier.weight(1f), sub = "depuis toujours")
                }
                if (a.sinceStart) {
                    Spacer(Modifier.height(8.dp))
                    Muted("Historique complet du compte : ${Money.eur(l.capitalNet, 0)} virés depuis l'ouverture, résultat ${Money.signedEur(a.lifetimeGain, 0)}. Change le point de départ dans Réglages.")
                }
            }
        }
    }
    // Que faire maintenant : la recommandation la plus utile en premier.
    item {
        val top = a.recs.values.filter { it.action.isBuy || it.action.isSell }.sortedByDescending { if (it.action.isSell) 100 - it.score else it.score }
        SectionTitle("À faire maintenant")
        Glass(onClick = { onTab(3) }) {
            if (top.isEmpty()) {
                Text(a.plan.headline, style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                a.plan.lines.filter { it.eur > 0 }.take(4).forEach { l -> Muted("${l.symbol} : ${Money.eur(l.eur, 0)}") }
                Muted("Touche pour le détail du plan DCA intelligent.")
            } else {
                top.take(3).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onAsset(r.symbol) }, verticalAlignment = Alignment.CenterVertically) {
                        CoinBadge(r.symbol, 34); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.symbol, style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                            Muted("Score ${r.score}/100 · confiance ${r.confidence.label}")
                        }
                        ActionBadge(r.action)
                    }
                }
                Muted("DCA intelligent : ${a.plan.headline}")
            }
        }
    }
    a.history?.let { h ->
        item {
            var range by remember { mutableStateOf(3) }
            val days = listOf(30, 90, 365, Int.MAX_VALUE)[range]
            val cut = System.currentTimeMillis() - days.toLong() * 86_400_000L
            val pts = h.points.filter { days == Int.MAX_VALUE || it.t >= cut }
            SectionTitle("Toi face au Bitcoin") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("1M", "3M", "1A", "Tout").forEachIndexed { i, s -> Pill(s, range == i, { range = i }) }
                }
            }
            Glass {
                LineChart(listOf(
                    Series("Toi", pts.map { it.t to it.value }, Xp.Cyan, fill = true),
                    Series("Même argent en BTC", pts.map { it.t to it.benchmark }, Xp.Gold, width = 1.6f),
                    Series("Capital", pts.map { it.t to it.capital }, Xp.Slate, dashed = true, width = 1.3f),
                ))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Tag("Ton portefeuille", Xp.Cyan); Tag("Même argent en BTC", Xp.Gold); Tag("Capital apporté", Xp.Slate)
                }
                if (pts.size > 1) {
                    val f = pts.first(); val e = pts.last()
                    val flows = pts.drop(1).sumOf { it.flow }
                    val mine = e.value - f.value - flows
                    val btc = e.benchmark - (if (f.benchmark.isNaN()) 0.0 else f.benchmark) - flows
                    val diff = mine - btc
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (diff >= 0) "Sur la période, tu fais mieux que le Bitcoin de ${Money.eur(diff)}." else "Sur la période, le Bitcoin fait mieux que toi de ${Money.eur(-diff)}.",
                        style = MaterialTheme.typography.bodyMedium, color = if (diff >= 0) Xp.Gain else Xp.Loss,
                    )
                }
                Muted("La courbe or : chaque euro viré sur Coinbase placé en Bitcoin le jour même. Les virements ne sont jamais comptés comme des gains.")
            }
        }
        item {
            SectionTitle("Performance")
            Glass {
                val (d, dPct) = a.day
                KV("24 heures", Money.signedEur(d), if (d >= 0) Xp.Gain else Xp.Loss, Money.pct(dPct, true))
                h.periods.forEach { p -> KV(p.label, Money.signedEur(p.gainEur), if (p.gainEur >= 0) Xp.Gain else Xp.Loss, Money.pct(p.pct, true) + " (pondéré par les virements)") }
                Divider()
                KV("Pire baisse historique", Money.pct(h.maxDrawdown), Xp.Loss)
                KV("Baisse actuelle depuis le sommet", Money.pct(h.currentDrawdown))
                if (!h.volatility.isNaN()) KV("Volatilité annuelle", Money.pct(h.volatility))
            }
        }
    }
    if (a.issues.isNotEmpty()) item {
        SectionTitle("Qualité des données")
        Glass { a.issues.forEach { Bullet(it, Xp.Amber) } }
    }
    item { Spacer(Modifier.height(8.dp)); Muted("SnipeX conseille, il ne passe jamais d'ordre. Aucun signal n'est une garantie de bénéfice.", Modifier.padding(horizontal = 8.dp)) }
}
