// Xplorer — moteur de signaux.
// Fonctions pures : aucune donnée inventée, abstention si l'historique est insuffisant.

export const ZONES = {
  green:  { key: 'green',  emoji: '🟢', label: 'Bon moment pour acheter' },
  yellow: { key: 'yellow', emoji: '🟡', label: 'Prix moyen' },
  orange: { key: 'orange', emoji: '🟠', label: 'Trop cher, on attend' },
  red:    { key: 'red',    emoji: '🔴', label: 'Moment de vendre' },
  none:   { key: 'none',   emoji: '⚪', label: 'Pas assez de données' },
};

export const MIN_HISTORY = 90;      // jours minimum pour donner un avis
const MIN_ORDER_EUR = 2;            // en dessous, on ne propose pas d'ordre

// ---------- indicateurs ----------

export function sma(values, n) {
  if (!values.length) return null;
  const slice = values.slice(-Math.min(n, values.length));
  return slice.reduce((a, b) => a + b, 0) / slice.length;
}

// RSI de Wilder
export function rsi(values, n = 14) {
  if (values.length < n + 1) return null;
  let gain = 0, loss = 0;
  for (let i = 1; i <= n; i++) {
    const d = values[i] - values[i - 1];
    if (d >= 0) gain += d; else loss -= d;
  }
  gain /= n; loss /= n;
  for (let i = n + 1; i < values.length; i++) {
    const d = values[i] - values[i - 1];
    gain = (gain * (n - 1) + Math.max(d, 0)) / n;
    loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
  }
  if (loss === 0) return 100;
  return 100 - 100 / (1 + gain / loss);
}

export function quantile(values, q) {
  const s = [...values].sort((a, b) => a - b);
  const pos = (s.length - 1) * q;
  const lo = Math.floor(pos), hi = Math.ceil(pos);
  return s[lo] + (s[hi] - s[lo]) * (pos - lo);
}

export function percentileRank(values, x) {
  if (!values.length) return null;
  return values.filter(v => v <= x).length / values.length;
}

// ---------- analyse d'une crypto ----------

// closes : clôtures journalières (anciennes -> récentes), price : prix actuel
export function analyze(closes, price) {
  if (!closes || closes.length < MIN_HISTORY || !(price > 0)) {
    return { zone: ZONES.none, enough: false, indicators: [], thresholds: null };
  }
  const series = [...closes, price];
  const year = closes.slice(-365);
  const ma200 = sma(closes, 200);
  const r = rsi(series.slice(-120));
  const pos = percentileRank(year, price);
  const dist = price / ma200 - 1;
  const high = Math.max(...year);
  const low = Math.min(...year);

  const buyBelow = quantile(year, 0.35);
  const expensiveAbove = quantile(year, 0.75);
  const sellAbove = Math.max(quantile(year, 0.9), ma200 * 1.3);

  const sellVotes = [pos >= 0.9, dist > 0.3, r >= 70].filter(Boolean).length;
  let zone;
  if (sellVotes >= 2 && pos >= 0.85) zone = ZONES.red;
  else if (pos >= 0.75 || r >= 70) zone = ZONES.orange;
  else if (pos <= 0.35 || (dist < -0.05 && r < 45)) zone = ZONES.green;
  else zone = ZONES.yellow;

  const indicators = [
    { side: 'buy',  label: 'Prix bas sur 1 an',              ok: pos <= 0.35, value: `${Math.round(pos * 100)} % des jours étaient moins chers` },
    { side: 'buy',  label: 'Sous la moyenne 200 jours',      ok: dist < 0,    value: `${fmtPct(dist, true)} vs moyenne` },
    { side: 'buy',  label: 'RSI en survente (< 35)',         ok: r < 35,      value: `RSI ${Math.round(r)}` },
    { side: 'buy',  label: 'Pas en surchauffe (RSI < 70)',   ok: r < 70,      value: `RSI ${Math.round(r)}` },
    { side: 'sell', label: 'Prix très haut sur 1 an',        ok: pos >= 0.9,  value: `${Math.round(pos * 100)} % des jours étaient moins chers` },
    { side: 'sell', label: '30 % au-dessus de la moyenne 200 j', ok: dist > 0.3, value: `${fmtPct(dist, true)} vs moyenne` },
    { side: 'sell', label: 'RSI en surchauffe (≥ 70)',       ok: r >= 70,     value: `RSI ${Math.round(r)}` },
  ];

  return {
    enough: true, zone, price, pos, ma200, dist, rsi: r, high, low,
    drawdown: price / high - 1,
    thresholds: { buyBelow, expensiveAbove, sellAbove },
    indicators,
  };
}

// ---------- portefeuille ----------

export function portfolio(assets, holdings, prices) {
  const rows = assets.map(a => {
    const qty = holdings[a.symbol] || 0;
    const price = prices[a.symbol]?.eur ?? null;
    return { ...a, qty, price, value: price != null ? qty * price : 0 };
  });
  const total = rows.reduce((s, r) => s + r.value, 0);
  rows.forEach(r => { r.weight = total > 0 ? r.value / total : 0; });
  return { rows, total };
}

// ---------- conseils de vente ----------

export function sellAdvice(row, an, total, rules) {
  if (!an?.enough || row.qty <= 0 || !(row.price > 0)) return null;

  // Règle GEOD : achat seulement, sauf si le prix a fait x20 depuis le prix d'achat
  if (row.symbol === 'GEOD') {
    const trigger = rules.geodBase * rules.geodMult;
    if (row.price >= trigger) {
      return {
        action: 'sell', decide: true, eur: null, qty: null,
        why: `Le GEOD a fait x${rules.geodMult} depuis ton prix d'achat (${fmtEur(rules.geodBase, 2)} → ${fmtEur(row.price, 2)}). C'est ta règle de sortie : tu décides combien tu vends.`,
      };
    }
    return null;
  }

  const targetValue = row.target * total;
  const excess = row.value - targetValue;
  const drift = row.target > 0 ? row.weight / row.target - 1 : null;
  const t = an.thresholds;

  if (an.zone.key === 'red') {
    let eur, reasonSize;
    if (excess >= MIN_ORDER_EUR && row.target > 0) {
      eur = excess;
      reasonSize = `ça ramène ${row.symbol} à ta cible de ${Math.round(row.target * 100)} %`;
    } else {
      eur = row.value * 0.2;
      reasonSize = `tu encaisses 20 % de ta position sans tout lâcher`;
    }
    if (eur < MIN_ORDER_EUR) return null;
    return {
      action: 'sell', eur, qty: eur / row.price,
      why: `Le prix est dans sa zone haute (${Math.round(an.pos * 100)} % des jours de l'année étaient moins chers, RSI ${Math.round(an.rsi)}). Vendre ${fmtEur(eur)} : ${reasonSize}.`,
      after: `Garde l'argent en euros et rachète quand ${row.symbol} repasse sous ${fmtEur(t.buyBelow, priceDecimals(t.buyBelow))}.`,
    };
  }

  if (an.zone.key === 'orange' && drift != null && drift > 0.2 && excess >= MIN_ORDER_EUR) {
    return {
      action: 'sell', eur: excess, qty: excess / row.price,
      why: `${row.symbol} pèse ${Math.round(row.weight * 100)} % de ton portefeuille au lieu de ${Math.round(row.target * 100)} %, et son prix est cher. Vendre ${fmtEur(excess)} te remet à ta cible.`,
      after: `Rachète sous ${fmtEur(t.buyBelow, priceDecimals(t.buyBelow))}.`,
    };
  }
  return null;
}

// ---------- plan du mois ----------

export function weeksLeftInMonth(date = new Date()) {
  const last = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
  const daysLeft = last - date.getDate() + 1;
  return Math.max(1, Math.ceil(daysLeft / 7));
}

export function monthlyPlan({ rows, total, analyses, amount, cash, date = new Date() }) {
  const notes = [];
  let budget = amount;
  if (cash != null && cash < amount) {
    notes.push(`Tu as ${fmtEur(cash)} disponibles sur Coinbase : le plan est calculé sur ce montant tant que ton versement n'est pas arrivé.`);
    budget = Math.max(0, cash);
  }
  const after = total + budget;
  const items = rows.filter(r => r.target > 0).map(r => {
    const an = analyses[r.symbol];
    const zone = an?.zone?.key ?? 'none';
    const gap = Math.max(0, r.target * after - r.value);
    const eligible = zone === 'green' || zone === 'yellow';
    const factor = zone === 'green' ? 1.5 : 1;
    return { symbol: r.symbol, name: r.name, row: r, an, zone, gap, eligible, weight: eligible ? gap * factor : 0, eur: 0 };
  });

  let sumW = items.reduce((s, i) => s + i.weight, 0);
  if (sumW === 0) {
    items.forEach(i => { i.weight = i.eligible ? i.row.target * (i.zone === 'green' ? 1.5 : 1) : 0; });
    sumW = items.reduce((s, i) => s + i.weight, 0);
  }
  if (sumW > 0 && budget > 0) {
    items.forEach(i => { i.eur = budget * i.weight / sumW; });
    // pas de miettes : on regroupe les petits montants sur la plus grosse ligne
    const small = items.filter(i => i.eur > 0 && i.eur < MIN_ORDER_EUR);
    const freed = small.reduce((s, i) => s + i.eur, 0);
    small.forEach(i => { i.eur = 0; });
    const biggest = items.reduce((m, i) => (i.eur > (m?.eur ?? 0) ? i : m), null);
    if (biggest) biggest.eur += freed;
  }

  const weeks = weeksLeftInMonth(date);
  items.forEach(i => {
    i.eur = Math.round(i.eur);
    i.weekEur = Math.round(i.eur / weeks);
    i.qty = i.row.price > 0 ? i.eur / i.row.price : null;
    i.why = buyReason(i);
  });

  const allocated = items.reduce((s, i) => s + i.eur, 0);
  const waiting = Math.max(0, Math.round(budget - allocated));
  if (waiting > 0) notes.push(`${fmtEur(waiting)} restent en attente : les cryptos concernées sont trop chères en ce moment.`);
  return { budget, weeks, items, waiting, notes };
}

function buyReason(i) {
  const an = i.an;
  if (!an?.enough) return `Pas assez d'historique de prix : on ne propose rien.`;
  const now = Math.round(i.row.weight * 100), target = Math.round(i.row.target * 100);
  const posTxt = `${Math.round(an.pos * 100)} % des jours de l'année étaient moins chers`;
  if (i.zone === 'orange' || i.zone === 'red') {
    return `Trop cher en ce moment (${posTxt}). On attend qu'il repasse sous ${fmtEur(an.thresholds.expensiveAbove, priceDecimals(an.thresholds.expensiveAbove))}.`;
  }
  if (i.eur <= 0) return `Déjà à sa cible (${now} % pour ${target} %).`;
  const under = now < target ? `Il est sous ta cible (${now} % au lieu de ${target} %)` : `Il reste à ta cible de ${target} %`;
  if (i.zone === 'green') return `${under} et le prix est bas : ${posTxt}.`;
  return `${under}. Prix moyen : on achète normalement, sans forcer.`;
}

// ---------- phrase d'accueil ----------

export function headline(row, an, sell, planItem) {
  if (!an?.enough) return `${ZONES.none.emoji} Pas assez de données pour juger.`;
  if (sell) {
    if (sell.decide) return `🔴 x20 atteint : à toi de décider combien vendre.`;
    return `🔴 Vends ${fmtEur(sell.eur)} (${fmtQty(sell.qty)} ${row.symbol}) : prix dans sa zone haute.`;
  }
  if (planItem && planItem.weekEur >= MIN_ORDER_EUR) {
    const z = an.zone.key === 'green' ? '🟢' : '🟡';
    return `${z} Achète ${fmtEur(planItem.weekEur)} cette semaine${an.zone.key === 'green' ? ' : le prix est bas.' : '.'}`;
  }
  const t = an.thresholds;
  if (an.zone.key === 'orange' || an.zone.key === 'red') return `🟠 On attend : sous ${fmtEur(t.expensiveAbove, priceDecimals(t.expensiveAbove))} ce serait mieux.`;
  if (an.zone.key === 'green') return `🟢 Bon prix, mais déjà à ta cible.`;
  return `🟡 Prix moyen, rien à faire.`;
}

// ---------- bulletin de notes ----------

export const GRADE_DAYS = 30;

export function recordSignals(log, signals, prices, now = Date.now()) {
  const day = new Date(now).toISOString().slice(0, 10);
  const out = [...log];
  for (const s of signals) {
    const exists = out.some(e => e.symbol === s.symbol && e.action === s.action && e.day === day);
    const p = prices[s.symbol]?.eur;
    if (!exists && p > 0) out.push({ day, t: now, symbol: s.symbol, action: s.action, price: p, eur: s.eur ?? null });
  }
  return out;
}

export function grade(log, prices, now = Date.now()) {
  const rows = log.map(e => {
    const age = (now - e.t) / 86400000;
    const p = prices[e.symbol]?.eur;
    if (age < GRADE_DAYS || !(p > 0)) return { ...e, status: 'pending', daysLeft: Math.ceil(GRADE_DAYS - age) };
    const move = p / e.price - 1;
    const good = e.action === 'buy' ? move >= 0 : move <= 0;
    return { ...e, status: good ? 'good' : 'bad', move };
  });
  const done = rows.filter(r => r.status !== 'pending');
  const good = done.filter(r => r.status === 'good').length;
  return { rows, done: done.length, good, score: done.length ? good / done.length : null };
}

// ---------- toi contre le marché ----------

// fills : [{t, symbol, side, qty}] ; history : {SYM: [{t, c}]}
// Le "marché" = chaque euro que tu as mis (ou retiré) placé en BTC le même jour.
export function performance(fills, history, bench = 'BTC') {
  const bh = history[bench];
  if (!fills.length || !bh?.length) return null;
  const sorted = [...fills].sort((a, b) => a.t - b.t);
  const dayKey = t => Math.floor(t / 86400000);
  const closeAt = (sym, t) => {
    const h = history[sym]; if (!h?.length) return null;
    const k = dayKey(t);
    let best = null;
    for (const p of h) { if (dayKey(p.t) <= k) best = p.c; else break; }
    return best ?? h[0].c;
  };
  const start = dayKey(sorted[0].t);
  const days = bh.filter(p => dayKey(p.t) >= start);
  const qty = {}; let btcBench = 0, invested = 0; let fi = 0;
  const mine = [], market = [], cash = [];
  for (const d of days) {
    const k = dayKey(d.t);
    while (fi < sorted.length && dayKey(sorted[fi].t) <= k) {
      const f = sorted[fi++];
      const px = f.eurPrice ?? closeAt(f.symbol, f.t);
      if (!(px > 0)) continue;
      const eur = f.qty * px * (f.side === 'BUY' ? 1 : -1);
      qty[f.symbol] = (qty[f.symbol] || 0) + (f.side === 'BUY' ? f.qty : -f.qty);
      invested += eur;
      btcBench += eur / closeAt(bench, f.t);
    }
    let v = 0;
    for (const [sym, q] of Object.entries(qty)) { const c = closeAt(sym, d.t); if (c) v += q * c; }
    mine.push({ t: d.t, v });
    market.push({ t: d.t, v: btcBench * d.c });
    cash.push({ t: d.t, v: invested });
  }
  const last = mine.length - 1;
  return {
    mine, market, invested: cash,
    mineNow: mine[last]?.v ?? 0, marketNow: market[last]?.v ?? 0, investedNow: cash[last]?.v ?? 0,
  };
}

// Toi face au marché, à partir de ce que tu possèdes vraiment.
// On remonte le temps : quantité d'hier = quantité d'aujourd'hui moins les achats faits depuis (plus les ventes).
// Le "marché" part de la même valeur que toi au début de la période, tout en BTC,
// et suit les mêmes achats et ventes en euros.
// holdings : {SYM: qty}, fills : [{t, symbol, side, qty, eurPrice}], history : {SYM: [{t, c}]}, now : {SYM: prix actuel}
export function portfolioVsMarket(holdings, fills, history, startT, now = {}, bench = 'BTC', base = 0) {
  const bh = history[bench];
  const syms = Object.keys(holdings).filter(s => holdings[s] > 0 && history[s]?.length);
  if (!bh?.length || !syms.length) return null;
  const DAY = 86400000;
  const closeAt = (sym, t) => {
    const h = history[sym]; let best = null;
    for (const p of h) { if (p.t <= t) best = p.c; else break; }
    return best ?? h[0].c;
  };
  const relevant = fills.filter(f => syms.includes(f.symbol) || f.symbol === bench).sort((a, b) => a.t - b.t);
  const qtyAt = (sym, t) => {
    let q = holdings[sym] || 0;
    for (const f of relevant) if (f.symbol === sym && f.t > t) q -= f.side === 'BUY' ? f.qty : -f.qty;
    return Math.max(0, q);
  };
  const days = bh.filter(p => p.t >= startT).map(p => p.t + DAY - 1); // fin de journée
  const nowT = Date.now();
  const points = [...days, nowT];
  const valueAt = t => syms.reduce((v, s) => {
    const px = t === nowT && now[s] ? now[s] : closeAt(s, t);
    return v + qtyAt(s, t) * (px || 0);
  }, 0);
  const mine = points.map(t => ({ t: Math.min(t, nowT), v: valueAt(t) }));
  if (mine.length < 2 || !(mine[0].v > 0)) return null;

  const btcPx = t => (t === nowT && now[bench] ? now[bench] : closeAt(bench, t));
  let btc = mine[0].v / btcPx(points[0]);
  let fi = relevant.findIndex(f => f.t > points[0]);
  if (fi < 0) fi = relevant.length;
  const market = points.map(t => {
    while (fi < relevant.length && relevant[fi].t <= t) {
      const f = relevant[fi++];
      const eur = f.qty * (f.eurPrice || closeAt(f.symbol, f.t)) * (f.side === 'BUY' ? 1 : -1);
      btc = Math.max(0, btc + eur / closeAt(bench, f.t));
    }
    return { t: Math.min(t, nowT), v: btc * btcPx(t) };
  });
  // Argent investi : tout ce que tu as mis (achats + frais) moins ce que tu as récupéré (ventes - frais)
  const flow = f => {
    const px = f.eurPrice || closeAt(f.symbol, f.t) || 0;
    const fee = f.quote === 'EUR' ? (f.fee || 0) : 0;
    return f.side === 'BUY' ? f.qty * px + fee : -(f.qty * px - fee);
  };
  const allFills = [...fills].sort((a, b) => a.t - b.t);
  const investedAt = t => base + allFills.filter(f => f.t <= t).reduce((s, f) => s + flow(f), 0);
  const invested = points.map(t => ({ t: Math.min(t, nowT), v: investedAt(t) }));
  const last = mine.length - 1;
  return {
    mine, market, invested,
    mineNow: mine[last].v, marketNow: market[last].v, investedNow: invested[last].v, start: mine[0].v,
    investedInPeriod: invested[last].v - invested[0].v,
  };
}

// ---------- formats ----------

export function priceDecimals(p) {
  if (p >= 1000) return 0;
  if (p >= 10) return 2;
  if (p >= 1) return 2;
  return 4;
}
export function fmtEur(v, d) {
  if (v == null || isNaN(v)) return '—';
  const dec = d ?? (Math.abs(v) >= 100 ? 0 : 2);
  return v.toLocaleString('fr-BE', { minimumFractionDigits: dec, maximumFractionDigits: dec }).replace(/\s/g, '\u00A0') + '\u00A0€';
}
export function fmtQty(q) {
  if (q == null || isNaN(q)) return '—';
  const d = q >= 100 ? 2 : q >= 1 ? 4 : 6;
  return q.toLocaleString('fr-BE', { maximumFractionDigits: d });
}
export function fmtPct(v, sign = false) {
  if (v == null || isNaN(v)) return '—';
  const s = (v * 100).toLocaleString('fr-BE', { maximumFractionDigits: 1 });
  return (sign && v > 0 ? '+' : '') + s + '\u00A0%';
}
