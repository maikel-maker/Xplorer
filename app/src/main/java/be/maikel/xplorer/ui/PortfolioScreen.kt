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
                    Text(p.symbol, style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                    Muted("${Money.qty(p.qty)} à ${if (p.priceKnown) Money.price(p.price) else "prix inconnu"}")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Money.eur(p.value), style = MaterialTheme.typography.titleMedium, color = Xp.Ice)
                    p.unrealized?.let { u ->
                        val base = p.costBasis
                        Text("${Money.signedEur(u)}${if (base > 0) " (" + Money.pct(u / base, true) + ")" else ""}", style = MaterialTheme.typography.labelMedium, color = if (u >= 0) Xp.Gain else Xp.Loss)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val target = st.targets[p.symbol]
            WeightBar(p.weight, target, color = if (p.weight > st.maxFor(p.symbol)) Xp.Loss else Xp.Cyan)
            Spacer(Modifier.height(6.dp))
            Row {
                Muted("${Money.pct(p.weight)} des cryptos" + (target?.let { " · cible ${Money.pct(it)}" } ?: " · hors répartition"), Modifier.weight(1f))
                Muted("PRU ${if (p.avgCost.isNaN()) "inconnu" else Money.price(p.avgCost)}")
            }
            if (p.unknownQty > 0 && p.priceKnown && p.unknownQty * p.price > 1) Muted("${Money.qty(p.unknownQty)} sans historique d'achat : prix de revient inconnu pour cette part.")
            if (p.rewardsQty > 0) Muted("Dont ${Money.qty(p.rewardsQty)} reçus en récompenses (coût zéro).")
            if (p.realized != 0.0) Muted("Gains réalisés : ${Money.signedEur(p.realized)} · frais payés : ${Money.eur(p.fees)}")
        }
    }
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
