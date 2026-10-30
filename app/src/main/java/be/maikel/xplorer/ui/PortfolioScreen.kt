package be.maikel.xplorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import be.maikel.xplorer.core.Analysis
import be.maikel.xplorer.domain.Money

fun LazyListScope.portfolioScreen(a: Analysis?, onAsset: (String) -> Unit) {
    if (a == null) { item { Muted("Chargement…") }; return }
    val st = a.snap.settings
    val l = a.ledger
    item {
        SectionTitle("Positions")
        if (l.positions.isEmpty()) Glass { Muted("Aucune crypto détenue, ou clé Coinbase pas encore branchée.") }
    }
    items(l.positions, key = { it.symbol }) { p ->
        Glass(Modifier.padding(bottom = 10.dp), onClick = { onAsset(p.symbol) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinBadge(p.symbol)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(coinName(p.symbol), style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                    Muted("${Money.qty(p.qty)} ${p.symbol}")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Money.eur(p.value), style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                    p.unrealized?.let { u ->
                        val base = p.costBasis
                        Text(if (u >= 0) "gagné ${Money.eur(u, 0)}" else "perdu ${Money.eur(-u, 0)}", style = MaterialTheme.typography.labelMedium, color = if (u >= 0) Xp.Gain else Xp.Loss)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val target = st.targets[p.symbol]
            WeightBar(p.weight, target, color = if (p.weight > st.maxFor(p.symbol)) Xp.Loss else Xp.Cyan)
            Spacer(Modifier.height(6.dp))
            Muted(target?.let { t ->
                val w = (p.weight * 100).toInt(); val tt = (t * 100).toInt()
                when {
                    p.weight > t * 1.1 -> "$w % de tes cryptos. Ton objectif : $tt %. Tu en as plus que prévu."
                    p.weight < t * 0.9 -> "$w % de tes cryptos. Ton objectif : $tt %. Il en manque un peu."
                    else -> "$w % de tes cryptos. Pile dans ton objectif ($tt %)."
                }
            } ?: "${(p.weight * 100).toInt()} % de tes cryptos. Pas dans ton plan.", small = false)
        }
    }
}

/** Santé, scénarios et répartition (onglet Détails). */
fun LazyListScope.healthScreen(a: Analysis?) {
    if (a == null) { item { Muted("Chargement…") }; return }
    val st = a.snap.settings
    val l = a.ledger
    item {
        SectionTitle("Carte de santé")
        Glass {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${a.health.score}", style = MaterialTheme.typography.displayLarge, color = when {
                    a.health.score >= 75 -> Xp.Gain; a.health.score >= 50 -> Xp.Amber; else -> Xp.Loss
                })
                Spacer(Modifier.width(10.dp))
                Muted("sur 100. Ce score résume la diversification, le risque et la qualité des données. Ce n'est pas une mesure absolue de sécurité.")
            }
            Spacer(Modifier.height(8.dp))
            a.health.items.forEach { (k, v) -> KV(k, v) }
            if (a.health.tips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Pistes d'amélioration", style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                a.health.tips.forEach { Bullet(it, Xp.Gold) }
            }
        }
    }
    item {
        SectionTitle("Et si le marché baisse ?")
        Glass {
            a.health.scenarios.forEach { s -> KV(s.label, Money.eur(s.valueAfter, 0), Xp.Ice, Money.signedEur(s.loss, 0)) }
            val maxLoss = st.maxLossPct * l.cryptoEur
            Spacer(Modifier.height(6.dp))
            Muted("Ta perte maximale envisagée : ${Money.pct(st.maxLossPct)} des cryptos, soit ${Money.eur(maxLoss, 0)}. Simulations déterministes, pas des prévisions.")
        }
    }
    item {
        SectionTitle("Répartition")
        Glass {
            KV("Cryptos", Money.eur(l.cryptoEur, 0))
            KV("Liquidités (EUR, USDC)", Money.eur(l.cashEur, 0))
            KV("Capital apporté", Money.eur(a.capital, 0), sub = "${Money.eur(l.capitalIn, 0)} virés, ${Money.eur(l.capitalOut, 0)} retirés")
            KV("Opérations Coinbase lues", "${l.opCount}")
        }
    }
}
