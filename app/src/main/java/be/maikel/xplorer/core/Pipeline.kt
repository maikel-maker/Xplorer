package be.maikel.xplorer.core

import android.content.Context
import be.maikel.xplorer.data.AlertEntity
import be.maikel.xplorer.data.JournalEntity
import be.maikel.xplorer.data.Repo
import be.maikel.xplorer.domain.Journal
import be.maikel.xplorer.domain.Money
import be.maikel.xplorer.work.Notifier
import java.util.Calendar
import kotlin.math.abs

/**
 * Après chaque synchronisation : journal des conseils, notation des anciens conseils,
 * rapprochement des « J'ai acheté », et notifications (sans doublons, heures silencieuses respectées).
 */
object Pipeline {

    suspend fun afterSync(ctx: Context, repo: Repo, a: Analysis, newOps: Int, notify: Boolean) {
        val dao = repo.dao
        val now = System.currentTimeMillis()
        val st = a.snap.settings
        val messages = mutableListOf<Triple<String, String, String>>() // kind, title, body

        // 1. Journal : nouvel enregistrement si l'action change (jamais de modification rétroactive).
        for ((sym, r) in a.recs) {
            if (!(r.price > 0)) continue
            val last = dao.lastJournal(sym)
            if (Journal.shouldRecord(last?.action, last?.t, r.action, now)) {
                dao.addJournal(JournalEntity(
                    t = now, symbol = sym, action = r.action.name, score = r.score, price = r.price,
                    confidence = r.confidence.label, risk = r.risk.label, horizonDays = Journal.HORIZON_DAYS,
                    invalidation = r.invalidation, reasons = (r.pros.take(3) + r.cons.take(3)).joinToString(" | "), version = r.strategyVersion,
                ))
                val owned = (a.ledger.positions.firstOrNull { it.symbol == sym }?.value ?: 0.0) > 1
                val relevant = owned || st.targets.containsKey(sym)
                if (last != null && relevant && (r.action.isBuy || r.action.isSell)) {
                    messages += Triple("signal", "$sym — ${r.action.label}", "Score ${r.score}/100, confiance ${r.confidence.label}. ${r.pros.firstOrNull() ?: r.zone}")
                }
            }
        }
        // 2. Notation à l'échéance, avec le prix de clôture du jour d'échéance (pas celui d'aujourd'hui).
        for (j in dao.journal()) {
            if (j.gradedAt != null) continue
            val due = j.t + j.horizonDays * Repo.DAY
            if (now < due) continue
            val dueDay = due / Repo.DAY
            val close = a.snap.daily[j.symbol]?.firstOrNull { it.t / Repo.DAY >= dueDay }?.close ?: a.snap.prices[j.symbol] ?: continue
            val o = Journal.grade(j.action, j.price, close)
            dao.grade(j.id, o.price, o.move, o.verdict, now)
        }
        // 3. « J'ai acheté » : rapprochement avec une exécution réelle (même crypto, −1 j/+5 j, montant ±35 %).
        val trades = a.ledger.trades
        for (i in dao.intents()) {
            if (i.matchedOpId != null) continue
            val m = trades.firstOrNull { t -> t.symbol == i.symbol && t.buy && t.t in (i.t - Repo.DAY)..(i.t + 5 * Repo.DAY) && abs(t.totalEur / i.eur - 1) < 0.35 }
            if (m != null) dao.matchIntent(i.id, m.ref)
        }
        // 4. Autres alertes.
        if (newOps > 0 && a.snap.lastSync != null) messages += Triple("coinbase", "Nouvelle opération Coinbase", "$newOps opération(s) synchronisée(s).")
        for (p in a.ledger.positions) {
            if (p.weight > st.maxFor(p.symbol) && p.value > 5)
                messages += Triple("risque", "${p.symbol} — exposition au-dessus de ta limite", "${Money.pct(p.weight)} de tes cryptos pour une limite de ${Money.pct(st.maxFor(p.symbol))}.")
        }

        if (!notify || !st.notifications) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val quiet = if (st.quietStart > st.quietEnd) hour >= st.quietStart || hour < st.quietEnd else hour in st.quietStart until st.quietEnd
        var sentToday = dao.alertsSince(now - Repo.DAY)
        for ((kind, title, body) in messages) {
            if (dao.sameAlertSince(title, now - Repo.DAY) > 0) continue
            dao.addAlert(AlertEntity(t = now, kind = kind, title = title, body = body))
            if (quiet || sentToday >= st.maxAlertsPerDay) continue
            Notifier.show(ctx, title, body)
            sentToday++
        }
    }
}
