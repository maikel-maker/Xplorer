// Analyse technique : chaque indicateur est classé positif, négatif ou neutre.
import { sma, rsi, fmtEur, priceDecimals } from './engine.js';

export function emaSeries(values, n) {
  const k = 2 / (n + 1);
  const out = [];
  let prev = values.slice(0, n).reduce((a, b) => a + b, 0) / Math.min(n, values.length);
  for (let i = 0; i < values.length; i++) {
    if (i < n - 1) { out.push(null); continue; }
    if (i === n - 1) { out.push(prev); continue; }
    prev = values[i] * k + prev * (1 - k);
    out.push(prev);
  }
  return out;
}

export function macd(values, fast = 12, slow = 26, sig = 9) {
  if (values.length < slow + sig + 2) return null;
  const ef = emaSeries(values, fast), es = emaSeries(values, slow);
  const line = values.map((_, i) => (ef[i] != null && es[i] != null ? ef[i] - es[i] : null)).filter(v => v != null);
  const signal = emaSeries(line, sig);
  const n = line.length - 1;
  const hist = line[n] - signal[n], prevHist = line[n - 1] - signal[n - 1];
  return { macd: line[n], signal: signal[n], hist, prevHist };
}

export function bollinger(values, n = 20, k = 2) {
  if (values.length < n) return null;
  const w = values.slice(-n);
  const m = w.reduce((a, b) => a + b, 0) / n;
  const sd = Math.sqrt(w.reduce((a, b) => a + (b - m) ** 2, 0) / n);
  return { mid: m, upper: m + k * sd, lower: m - k * sd };
}

export const FIB_RATIOS = [0.236, 0.382, 0.5, 0.618, 0.786];

// Retracements de Fibonacci sur la dernière grande vague de l'année
export function fibonacci(closes) {
  const year = closes.slice(-365);
  if (year.length < 30) return null;
  let hi = -Infinity, lo = Infinity, hiI = 0, loI = 0;
  year.forEach((v, i) => { if (v > hi) { hi = v; hiI = i; } if (v < lo) { lo = v; loI = i; } });
  const up = loI < hiI; // vague montante : on mesure les replis depuis le sommet
  const levels = FIB_RATIOS.map(r => ({ r, v: up ? hi - (hi - lo) * r : lo + (hi - lo) * r }));
  return { high: hi, low: lo, up, levels };
}

const fibPct = r => `${(r * 100).toLocaleString('fr-BE', { maximumFractionDigits: 1 })} %`;
const P = 'pos', N = 'neg', Z = 'neu';

export function technical(closes, price) {
  if (!closes || closes.length < 60 || !(price > 0)) return null;
  const s = [...closes, price];
  const fp = v => fmtEur(v, priceDecimals(v));
  const items = [];

  // RSI
  const r = rsi(s.slice(-150));
  items.push({
    key: 'rsi', name: 'RSI (14 j)', value: Math.round(r),
    status: r < 35 ? P : r > 65 ? N : Z,
    text: r < 30 ? 'Survendu : la baisse s\'essouffle, un rebond est souvent proche.'
      : r < 35 ? 'Proche de la survente : vendeurs fatigués.'
      : r > 70 ? 'Surchauffe : beaucoup d\'acheteurs d\'un coup, repli fréquent ensuite.'
      : r > 65 ? 'Ça chauffe : prudence sur les achats.'
      : 'Zone calme, ni excès d\'achat ni excès de vente.',
  });

  // MACD
  const m = macd(s);
  if (m) {
    const crossUp = m.hist > 0 && m.prevHist <= 0, crossDown = m.hist < 0 && m.prevHist >= 0;
    const rising = m.hist > m.prevHist;
    let status = Z, text = 'Élan plat : le marché hésite.';
    if (crossUp) { status = P; text = 'Croisement haussier tout juste formé : l\'élan repart vers le haut.'; }
    else if (crossDown) { status = N; text = 'Croisement baissier tout juste formé : l\'élan se retourne.'; }
    else if (m.hist > 0 && rising) { status = P; text = 'Élan haussier qui se renforce.'; }
    else if (m.hist < 0 && !rising) { status = N; text = 'Élan baissier qui se renforce.'; }
    else if (m.hist > 0) { status = Z; text = 'Élan haussier, mais il faiblit.'; }
    else { status = Z; text = 'Élan baissier, mais il faiblit : à surveiller.'; }
    items.push({ key: 'macd', name: 'MACD (12, 26, 9)', value: m.hist > 0 ? 'haussier' : 'baissier', status, text });
  }

  // Moyennes mobiles
  for (const n of [20, 50, 200]) {
    if (closes.length < n) continue;
    const ma = sma(s, n);
    const d = price / ma - 1;
    const label = n === 20 ? 'court terme' : n === 50 ? 'moyen terme' : 'long terme';
    items.push({
      key: `ma${n}`, name: `Moyenne ${n} j`, value: fp(ma),
      status: Math.abs(d) < 0.01 ? Z : d > 0 ? P : N,
      text: Math.abs(d) < 0.01 ? `Prix collé à la moyenne ${label} : décision en cours.`
        : d > 0 ? `Prix ${Math.round(d * 100)} % au-dessus : tendance ${label} haussière.`
        : `Prix ${Math.round(-d * 100)} % en dessous : tendance ${label} baissière.`,
    });
  }
  if (closes.length >= 200) {
    const m50 = sma(s, 50), m200 = sma(s, 200);
    const m50p = sma(s.slice(0, -10), 50), m200p = sma(s.slice(0, -10), 200);
    const golden = m50 > m200, recent = (m50 > m200) !== (m50p > m200p);
    items.push({
      key: 'cross', name: 'Croisement 50 / 200', value: golden ? 'doré' : 'de la mort',
      status: golden ? P : N,
      text: golden ? `Moyenne 50 j au-dessus de la 200 j${recent ? ', croisement tout récent' : ''} : grande tendance haussière.`
        : `Moyenne 50 j sous la 200 j${recent ? ', croisement tout récent' : ''} : grande tendance baissière.`,
    });
  }

  // Bandes de Bollinger
  const b = bollinger(s);
  if (b) {
    const pct = (price - b.lower) / (b.upper - b.lower);
    items.push({
      key: 'boll', name: 'Bandes de Bollinger', value: `${Math.round(pct * 100)} %`,
      status: pct < 0.1 ? P : pct > 0.9 ? N : Z,
      text: pct < 0.1 ? 'Prix collé au bas des bandes : excès de baisse.'
        : pct > 0.9 ? 'Prix collé au haut des bandes : excès de hausse.'
        : 'Prix au milieu des bandes : mouvement normal.',
    });
  }

  // Fibonacci
  const f = fibonacci(closes);
  if (f) {
    const below = f.levels.filter(l => l.v <= price).sort((a, b2) => b2.v - a.v)[0];
    const above = f.levels.filter(l => l.v > price).sort((a, b2) => a.v - b2.v)[0];
    const near = (l, side) => l && Math.abs(price / l.v - 1) < 0.03 && side;
    let status = Z, text;
    if (near(below, true) && [0.5, 0.618].includes(below.r)) { status = P; text = `Prix posé sur le support ${fibPct(below.r)} (${fp(below.v)}) : niveau où les rebonds sont fréquents.`; }
    else if (near(above, true)) { status = N; text = `Résistance ${fibPct(above.r)} juste au-dessus (${fp(above.v)}) : le prix peut buter dessus.`; }
    else text = `Entre ${below ? `le support ${fp(below.v)}` : 'le plus bas'} et ${above ? `la résistance ${fp(above.v)}` : 'le plus haut'}.`;
    items.push({ key: 'fib', name: 'Fibonacci', value: below ? `${fibPct(below.r)}` : '—', status, text, fib: f });
  }

  const pos = items.filter(i => i.status === P).length;
  const neg = items.filter(i => i.status === N).length;
  const neu = items.length - pos - neg;
  const score = items.length ? (pos - neg) / items.length : 0; // -1 … +1
  let summary;
  if (score >= 0.35) summary = `Majorité de signaux positifs (${pos} sur ${items.length}) : le contexte technique est favorable.`;
  else if (score <= -0.35) summary = `Majorité de signaux négatifs (${neg} sur ${items.length}) : le contexte technique est défavorable.`;
  else summary = `Signaux partagés (${pos} positifs, ${neg} négatifs, ${neu} neutres) : pas de direction claire.`;
  return { items, pos, neg, neu, score, summary, fib: f, rsi: r };
}
