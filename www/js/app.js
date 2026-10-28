import * as E from './engine.js';
import * as M from './market.js';
import * as S from './store.js';
import * as CB from './coinbase.js';
import { lineChart, wireCharts } from './chart.js';
import { notify, askNotifications } from './native.js';

const { fmtEur, fmtQty, fmtPct, priceDecimals } = E;
const fmtPrice = p => fmtEur(p, priceDecimals(p ?? 0));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const $app = document.getElementById('app');

const st = {
  view: 'home', coin: null, range: '1A', sheet: null,
  settings: S.settings(),
  balances: S.load('balances'),
  fills: S.load('fills', []),
  prices: S.load('prices', {}),
  hist: {},
  lastUpdate: S.load('lastUpdate'),
  loading: false, progress: '', error: null, cbError: null,
  searchResults: null,
};
for (const a of st.settings.assets) st.hist[a.symbol] = S.load(`hist.${a.symbol}`)?.data || [];

// ================= calcul =================

function derive() {
  const s = st.settings;
  const usdc = st.prices.USDC?.eur ?? 0.92;
  let cash = null; const holdings = {};
  if (st.balances) {
    cash = 0;
    for (const [cur, v] of Object.entries(st.balances)) {
      if (cur === 'EUR' || cur === 'EURC') cash += v;
      else if (cur === 'USDC' || cur === 'USD' || cur === 'USDT') cash += v * usdc;
      else holdings[cur] = v;
    }
  }
  const analyses = {};
  for (const a of s.assets) {
    analyses[a.symbol] = E.analyze((st.hist[a.symbol] || []).map(p => p.c), st.prices[a.symbol]?.eur);
  }
  // Règle GEOD : sous le seuil x20, jamais de « Moment de vendre », seulement « Trop cher, on attend »
  const geodExit = s.geodBase * s.geodMult;
  const g = analyses.GEOD;
  if (g?.enough && g.zone.key === 'red' && !(st.prices.GEOD?.eur >= geodExit)) analyses.GEOD = { ...g, zone: E.ZONES.orange };
  const pf = E.portfolio(s.assets, holdings, st.prices);
  const rules = { geodBase: s.geodBase, geodMult: s.geodMult };
  const sells = {};
  for (const r of pf.rows) { const sa = E.sellAdvice(r, analyses[r.symbol], pf.total, rules); if (sa) sells[r.symbol] = sa; }
  const plan = E.monthlyPlan({ rows: pf.rows, total: pf.total, analyses, amount: s.monthly, cash });
  const planBy = Object.fromEntries(plan.items.map(i => [i.symbol, i]));
  const heads = {};
  for (const r of pf.rows) heads[r.symbol] = E.headline(r, analyses[r.symbol], sells[r.symbol], planBy[r.symbol]);
  const todo = [
    ...Object.entries(sells).map(([sym, sa]) => ({ symbol: sym, action: 'sell', eur: sa.eur, text: heads[sym] })),
    ...plan.items.filter(i => i.weekEur >= 2 && !sells[i.symbol]).map(i => ({ symbol: i.symbol, action: 'buy', eur: i.weekEur, text: heads[i.symbol] })),
  ];
  const fillsEur = st.fills.map(f => ({ ...f, eurPrice: fillEurPrice(f) }));
  const perf = E.performance(fillsEur.filter(f => st.hist[f.symbol]?.length), st.hist);
  const change24 = pf.rows.reduce((t, r) => { const c = st.prices[r.symbol]?.ch24; return c != null ? t + r.value - r.value / (1 + c) : t; }, 0);
  return { cash, holdings, analyses, pf, sells, plan, planBy, heads, todo, perf, fillsEur, change24 };
}

function closeAt(sym, t) {
  const h = st.hist[sym]; if (!h?.length) return null;
  let best = h[0].c; for (const p of h) { if (p.t <= t) best = p.c; else break; }
  return best;
}
function fillEurPrice(f) {
  if (f.quote === 'EUR') return f.price;
  return closeAt(f.symbol, f.t);
}

// ================= mise à jour =================

async function refresh({ force = false } = {}) {
  if (st.loading) return;
  st.loading = true; st.error = null; st.progress = 'Prix du marché…'; render();
  const s = st.settings;
  try {
    const creds = S.creds();
    if (creds) {
      try {
        st.progress = 'Lecture de Coinbase…'; render();
        st.balances = await CB.fetchBalances(creds);
        st.fills = await CB.fetchFills(creds);
        S.save('balances', st.balances); S.save('fills', st.fills);
        st.cbError = null;
        await addHeldCoins();
      } catch (e) { st.cbError = e.message; }
    }
    st.prices = { ...st.prices, ...(await M.prices(s.assets)) };
    S.save('prices', st.prices);
    st.progress = 'Historique des prix…'; render();
    st.hist = await M.histories(s.assets, sym => { st.progress = `Historique ${sym}…`; });
    if (force) for (const a of s.assets) st.hist[a.symbol] = await M.history(a, { force: true });
    st.lastUpdate = Date.now(); S.save('lastUpdate', st.lastUpdate);
    afterUpdate();
  } catch (e) {
    st.error = 'Pas de connexion aux prix. Réessaie dans un moment.';
  }
  st.loading = false; st.progress = ''; render();
}

// Les cryptos que tu possèdes mais qui ne sont pas dans ta liste sont ajoutées (cible 0 %)
async function addHeldCoins() {
  const known = new Set(st.settings.assets.map(a => a.symbol));
  const missing = Object.keys(st.balances || {}).filter(c => !S.CASH.includes(c) && !known.has(c));
  if (!missing.length) return;
  for (const sym of missing) {
    try {
      const res = await M.search(sym);
      const hit = res.find(r => r.symbol === sym);
      st.settings.assets.push({ symbol: sym, name: hit?.name || sym, cg: hit?.cg || null, target: 0 });
    } catch { st.settings.assets.push({ symbol: sym, name: sym, cg: null, target: 0 }); }
  }
  S.saveSettings(st.settings);
}

// Notifications + bulletin : seulement quand un conseil change
function afterUpdate() {
  const d = derive();
  const prev = S.load('lastSignals', null);
  const now = {};
  for (const t of d.todo) now[t.symbol] = t.action;
  const fresh = d.todo.filter(t => !prev || prev[t.symbol] !== t.action);
  if (prev && st.settings.notifications) {
    for (const t of fresh) notify(`Xplorer · ${t.symbol}`, t.text.replace(/^\S+\s/, ''));
  }
  S.save('lastSignals', now);
  if (fresh.length) {
    const log = E.recordSignals(S.load('log', []), fresh.map(t => ({ symbol: t.symbol, action: t.action, eur: t.eur })), st.prices);
    S.save('log', log);
  }
  // alertes de prix perso
  let changed = false;
  for (const al of st.settings.alerts) {
    const p = st.prices[al.symbol]?.eur;
    if (al.done || !(p > 0)) continue;
    if ((al.dir === 'above' && p >= al.price) || (al.dir === 'below' && p <= al.price)) {
      al.done = true; changed = true;
      if (st.settings.notifications) notify(`Alerte ${al.symbol}`, `${al.symbol} est ${al.dir === 'above' ? 'au-dessus' : 'en dessous'} de ${fmtPrice(al.price)} : ${fmtPrice(p)}.`);
    }
  }
  if (changed) S.saveSettings(st.settings);
}

// ================= rendu =================

const COLORS = { BTC: '#F7931A', ETH: '#8C8DFC', GEOD: '#3DDC97', LINK: '#2A5ADA', TAO: '#C9CED6', KRL: '#13B5EA', SOL: '#9945FF' };
const badge = sym => `<span class="badge" style="--c:${COLORS[sym] || '#6B7385'}">${esc(sym.slice(0, 4))}</span>`;
const zoneChip = an => `<span class="chip z-${an?.zone?.key || 'none'}">${an?.zone?.emoji || '⚪'} ${an?.zone?.label || 'Pas assez de données'}</span>`;

const ICONS = {
  home: '<path d="M4 11.5 12 5l8 6.5V20a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z"/>',
  plan: '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 10h16M9 3v4M15 3v4"/>',
  grade: '<path d="M12 3l2.6 5.6 6 .7-4.5 4.1 1.2 6L12 16.5 6.7 19.4l1.2-6L3.4 9.3l6-.7z"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19 12a7 7 0 0 0-.1-1.2l2-1.6-2-3.4-2.4 1a7 7 0 0 0-2-1.2L14 3h-4l-.5 2.6a7 7 0 0 0-2 1.2l-2.4-1-2 3.4 2 1.6A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.6 2 3.4 2.4-1a7 7 0 0 0 2 1.2L10 21h4l.5-2.6a7 7 0 0 0 2-1.2l2.4 1 2-3.4-2-1.6c.1-.4.1-.8.1-1.2z"/>',
  refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
};
const icon = (n, cls = '') => `<svg class="ic ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${ICONS[n]}</svg>`;

function render() {
  const d = derive();
  let body;
  if (st.view === 'coin' && st.coin) body = viewCoin(d, st.coin);
  else if (st.view === 'plan') body = viewPlan(d);
  else if (st.view === 'grade') body = viewGrade();
  else if (st.view === 'settings') body = viewSettings(d);
  else body = viewHome(d);
  const nav = st.view === 'coin' ? '' : `<nav class="tabs">${[
    ['home', 'Accueil'], ['plan', 'Plan'], ['grade', 'Bulletin'], ['settings', 'Réglages'],
  ].map(([v, l]) => `<button data-go="${v}" class="${st.view === v ? 'on' : ''}">${icon(v === 'grade' ? 'grade' : v)}<span>${l}</span></button>`).join('')}</nav>`;
  $app.innerHTML = `<main class="view">${body}</main>${nav}${st.sheet === 'todo' ? sheetTodo(d) : ''}`;
  wireCharts($app, v => fmtEur(v, priceDecimals(v)));
}

function topBar(title, { back = false } = {}) {
  const upd = st.loading ? esc(st.progress) : st.lastUpdate ? `Mis à jour ${new Date(st.lastUpdate).toLocaleTimeString('fr-BE', { hour: '2-digit', minute: '2-digit' })}` : '';
  return `<header class="top">
    ${back ? `<button class="icon-btn" data-go="home" aria-label="Retour">${icon('back')}</button>` : ''}
    <div class="top-t"><h1>${title}</h1><small>${upd}</small></div>
    <button class="icon-btn ${st.loading ? 'spin' : ''}" data-act="refresh" aria-label="Actualiser">${icon('refresh')}</button>
  </header>${st.error ? `<div class="banner err">${esc(st.error)}</div>` : ''}`;
}

// ---------- Accueil ----------
function viewHome(d) {
  const hasKey = !!S.creds();
  const total = d.pf.total + (d.cash || 0);
  let hero;
  if (!hasKey) {
    hero = `<section class="hero">
      <div class="lbl">Ton portefeuille</div>
      <p class="hero-empty">Branche ta clé Coinbase en <b>lecture seule</b> pour voir ton portefeuille, tes achats sur les graphiques et ta courbe contre le marché.</p>
      <button class="btn" data-go="settings">Ajouter ma clé Coinbase</button>
    </section>`;
  } else {
    const ch = d.change24;
    const perf = d.perf;
    let perfBlock = '';
    if (perf && perf.mine.length > 2) {
      const diff = perf.mineNow - perf.marketNow;
      perfBlock = `<div class="vs">
        ${lineChart({ height: 150, label: 'Toi contre le marché', series: [
          { name: 'Toi', points: perf.mine, color: 'var(--accent)', fill: true },
          { name: 'Marché', points: perf.market, color: 'var(--violet)', width: 1.6 },
        ] })}
        <div class="legend"><span><i style="background:var(--accent)"></i>Toi</span><span><i style="background:var(--violet)"></i>Même argent en BTC</span></div>
        <p class="verdict ${diff >= 0 ? 'up' : 'down'}">${diff >= 0 ? 'Tu bats le marché' : 'Le marché fait mieux'} de <b>${fmtEur(Math.abs(diff))}</b> sur tes achats Coinbase.</p>
      </div>`;
    }
    hero = `<section class="hero">
      <div class="lbl">Ton portefeuille</div>
      <div class="total">${fmtEur(total, 0)}</div>
      <div class="sub"><span class="${ch >= 0 ? 'up' : 'down'}">${ch >= 0 ? '+' : '−'}${fmtEur(Math.abs(ch))} en 24 h</span> · cryptos ${fmtEur(d.pf.total, 0)} · cash ${fmtEur(d.cash || 0, 0)}</div>
      ${st.cbError ? `<div class="banner err">${esc(st.cbError)}</div>` : ''}
      ${perfBlock}
    </section>`;
  }
  const n = d.todo.length;
  const pill = `<button class="todo ${n ? 'has' : ''}" data-act="todo">
    <span class="dot"></span><span>${n ? `À faire aujourd'hui` : 'Rien à faire aujourd\'hui'}</span>${n ? `<b>${n}</b>` : ''}
  </button>`;
  const rows = d.pf.rows.filter(r => r.target > 0 || r.qty > 0).sort((a, b) => b.target - a.target || b.value - a.value).map(r => {
    const p = st.prices[r.symbol];
    return `<button class="row" data-coin="${esc(r.symbol)}">
      ${badge(r.symbol)}
      <div class="row-m">
        <div class="row-t"><b>${esc(r.name)}</b><span>${hasKey ? fmtEur(r.value) : fmtPrice(p?.eur)}</span></div>
        <div class="row-s">${esc(d.heads[r.symbol])}</div>
      </div>
    </button>`;
  }).join('');
  return `${topBar('Xplorer')}${hero}${pill}<section class="list">${rows}</section>
    <p class="foot">Xplorer conseille, il ne passe jamais d'ordre. Ce sont des calculs, pas des garanties.</p>`;
}

function sheetTodo(d) {
  const items = d.todo.map(t => `<button class="row" data-coin="${esc(t.symbol)}">${badge(t.symbol)}<div class="row-m"><div class="row-t"><b>${esc(t.symbol)}</b><span class="${t.action === 'sell' ? 'down' : 'up'}">${t.action === 'sell' ? 'Vendre' : 'Acheter'}</span></div><div class="row-s">${esc(t.text)}</div></div></button>`).join('');
  return `<div class="scrim" data-act="close"></div><div class="sheet"><div class="grab"></div><h2>À faire aujourd'hui</h2>
    ${items || '<p class="muted">Rien à faire : on laisse le marché venir à nous.</p>'}
    <p class="muted small">Tu passes les ordres toi-même sur Coinbase Advanced.</p></div>`;
}

// ---------- Fiche crypto ----------
function viewCoin(d, sym) {
  const a = st.settings.assets.find(x => x.symbol === sym);
  const r = d.pf.rows.find(x => x.symbol === sym);
  const an = d.analyses[sym];
  const p = st.prices[sym];
  const h = st.hist[sym] || [];
  const days = { '3M': 92, '1A': 365, 'Tout': 10000 }[st.range];
  const cut = Date.now() - days * 864e5;
  const pts = [...h.filter(x => x.t >= cut).map(x => ({ t: x.t, v: x.c })), ...(p?.eur ? [{ t: Date.now(), v: p.eur }] : [])];
  const fills = d.fillsEur.filter(f => f.symbol === sym && f.eurPrice > 0);
  const lines = [];
  if (an?.enough) {
    lines.push({ v: an.thresholds.buyBelow, color: 'var(--green)', label: `Bon achat < ${fmtPrice(an.thresholds.buyBelow)}` });
    if (sym !== 'GEOD') lines.push({ v: an.thresholds.sellAbove, color: 'var(--red)', label: `Vente > ${fmtPrice(an.thresholds.sellAbove)}` });
  }
  const geodExit = st.settings.geodBase * st.settings.geodMult;
  // la ligne de sortie GEOD n'est tracée que si elle est proche, sinon elle écrase la courbe
  const maxPt = Math.max(...pts.map(x => x.v), 0);
  if (sym === 'GEOD' && geodExit <= maxPt * 1.6) lines.push({ v: geodExit, color: 'var(--red)', label: `Ta sortie x${st.settings.geodMult} : ${fmtPrice(geodExit)}` });
  const chart = lineChart({
    height: 220, label: `Prix ${sym}`,
    series: [{ name: sym, points: pts, color: 'var(--accent)', fill: true }],
    markers: fills.map(f => ({ t: f.t, v: f.eurPrice, kind: f.side === 'BUY' ? 'buy' : 'sell' })),
    lines,
  });

  // conseil
  const sell = d.sells[sym], pl = d.planBy[sym];
  let advice;
  if (!an?.enough) advice = `<p>Pas assez d'historique de prix pour donner un avis honnête. Xplorer préfère se taire que deviner.</p>`;
  else if (sell?.decide) advice = `<div class="act sell"><b>Vendre : à toi de décider combien</b></div><p>${esc(sell.why)}</p>`;
  else if (sell) advice = `<div class="act sell"><b>Vends ${fmtEur(sell.eur)}</b><span>≈ ${fmtQty(sell.qty)} ${esc(sym)}</span></div><p><b>Pourquoi :</b> ${esc(sell.why)}</p><p><b>Ensuite :</b> ${esc(sell.after)}</p>`;
  else if (pl && pl.eur > 0) advice = `<div class="act buy"><b>Achète ${fmtEur(pl.weekEur)} cette semaine</b><span>${fmtEur(pl.eur)} sur le mois · ≈ ${fmtQty(pl.qty)} ${esc(sym)}</span></div><p><b>Pourquoi :</b> ${esc(pl.why)}</p>`;
  else if (pl) advice = `<div class="act wait"><b>On attend</b></div><p>${esc(pl.why)}</p>`;
  else advice = `<p>${esc(d.heads[sym] || '')}${r?.target === 0 ? ' Cette crypto n\'a pas de cible dans ta répartition : Xplorer ne propose pas d\'achat.' : ''}</p>`;
  if (sym === 'GEOD' && !sell) advice += `<p class="muted small">Règle GEOD : achat seulement. Vente proposée seulement à partir de ${fmtPrice(geodExit)} (x${st.settings.geodMult} depuis ${fmtPrice(st.settings.geodBase)}).</p>`;

  // position
  let pos = '';
  if (r && r.qty > 0) {
    const buys = fills.filter(f => f.side === 'BUY');
    const bq = buys.reduce((t, f) => t + f.qty, 0);
    const avg = bq > 0 ? buys.reduce((t, f) => t + f.qty * f.eurPrice, 0) / bq : null;
    const gain = avg ? r.value - avg * r.qty : null;
    pos = `<section class="card"><h3>Ta position</h3><div class="kv">
      <div><span>Quantité</span><b>${fmtQty(r.qty)} ${esc(sym)}</b></div>
      <div><span>Valeur</span><b>${fmtEur(r.value)}</b></div>
      <div><span>Part du portefeuille</span><b>${Math.round(r.weight * 100)} % <small>cible ${Math.round(r.target * 100)} %</small></b></div>
      <div><span>Prix moyen d'achat</span><b>${avg ? fmtPrice(avg) : '—'}</b></div>
      ${gain != null ? `<div><span>Gain / perte</span><b class="${gain >= 0 ? 'up' : 'down'}">${gain >= 0 ? '+' : '−'}${fmtEur(Math.abs(gain))} (${fmtPct(gain / (avg * r.qty), true)})</b></div>` : ''}
    </div>${avg ? '' : '<p class="muted small">Prix moyen calculé à partir de tes achats Coinbase (les cryptos reçues autrement ne sont pas comptées).</p>'}</section>`;
  }

  const ind = an?.enough ? `<section class="card"><h3>Indicateurs</h3>
    ${['buy', 'sell'].map(side => `<div class="ind-h">${side === 'buy' ? 'Signaux d\'achat' : 'Signaux de vente'}</div>` +
      an.indicators.filter(i => i.side === side).map(i => `<div class="ind ${i.ok ? 'ok' : ''}"><span class="tick">${i.ok ? '✓' : '·'}</span><span>${esc(i.label)}</span><small>${esc(i.value)}</small></div>`).join('')).join('')}
    <div class="kv small"><div><span>Moyenne 200 jours</span><b>${fmtPrice(an.ma200)}</b></div><div><span>Plus haut sur 1 an</span><b>${fmtPrice(an.high)} <small>(${fmtPct(an.drawdown)})</small></b></div></div>
  </section>` : '';

  const alerts = st.settings.alerts.filter(x => x.symbol === sym);
  const alertCard = `<section class="card"><h3>Alerte de prix</h3>
    ${alerts.map(al => `<div class="alert-row ${al.done ? 'done' : ''}"><span>${al.dir === 'above' ? 'Au-dessus de' : 'En dessous de'} ${fmtPrice(al.price)}${al.done ? ' · déclenchée' : ''}</span><button class="link" data-act="del-alert" data-id="${al.id}">Supprimer</button></div>`).join('')}
    <form class="alert-form" data-form="alert" data-sym="${esc(sym)}">
      <select name="dir"><option value="below">En dessous de</option><option value="above">Au-dessus de</option></select>
      <input name="price" type="number" step="any" inputmode="decimal" placeholder="Prix en €" required>
      <button class="btn sm">Ajouter</button>
    </form></section>`;

  const hist = fills.length ? `<section class="card"><h3>Tes achats et ventes</h3>${[...fills].reverse().slice(0, 30).map(f => `
    <div class="fill"><span class="dotc ${f.side === 'BUY' ? 'buy' : 'sell'}"></span><span>${new Date(f.t).toLocaleDateString('fr-BE')}</span><span>${f.side === 'BUY' ? 'Achat' : 'Vente'} ${fmtQty(f.qty)}</span><b>${fmtPrice(f.eurPrice)}</b></div>`).join('')}</section>` : '';

  return `${topBar(esc(a?.name || sym), { back: true })}
    <section class="coin-head">
      <div class="price">${fmtPrice(p?.eur)}</div>
      ${p?.ch24 != null ? `<div class="${p.ch24 >= 0 ? 'up' : 'down'}">${fmtPct(p.ch24, true)} en 24 h</div>` : ''}
      ${zoneChip(an)}
    </section>
    <div class="ranges">${['3M', '1A', 'Tout'].map(k => `<button data-range="${k}" class="${st.range === k ? 'on' : ''}">${k}</button>`).join('')}</div>
    ${chart}
    <div class="legend"><span><i style="background:var(--green)"></i>Tes achats</span><span><i style="background:var(--red)"></i>Tes ventes</span></div>
    <section class="card advice"><h3>Ce qu'il faut faire</h3>${advice}</section>
    ${pos}${ind}${alertCard}${hist}`;
}

// ---------- Plan du mois ----------
function viewPlan(d) {
  const pl = d.plan;
  const month = new Date().toLocaleDateString('fr-BE', { month: 'long' });
  const sells = Object.entries(d.sells).map(([sym, s]) => `<button class="row" data-coin="${esc(sym)}">${badge(sym)}<div class="row-m"><div class="row-t"><b>${esc(sym)}</b><span class="down">${s.decide ? 'À décider' : `Vendre ${fmtEur(s.eur)}`}</span></div><div class="row-s">${esc(s.why)}</div></div></button>`).join('');
  const items = pl.items.map(i => `<button class="row plan-row" data-coin="${esc(i.symbol)}">
    ${badge(i.symbol)}
    <div class="row-m">
      <div class="row-t"><b>${esc(i.name)}</b><span>${i.eur > 0 ? fmtEur(i.weekEur) + ' <small>/ sem.</small>' : '<small>0 €</small>'}</span></div>
      <div class="row-s">${i.eur > 0 ? `${fmtEur(i.eur)} ce mois · ≈ ${fmtQty(i.qty)} ${esc(i.symbol)}. ` : ''}${esc(i.why)}</div>
    </div>
  </button>`).join('');
  return `${topBar('Plan du mois')}
    <section class="card">
      <h3>Ton versement de ${esc(month)}</h3>
      <form class="amount" data-form="monthly">
        <input name="monthly" type="number" min="0" step="10" inputmode="numeric" value="${st.settings.monthly}">
        <span>€</span><button class="btn sm">OK</button>
      </form>
      <div class="chips-row">${[500, 600, 750, 1000].map(v => `<button class="pill ${st.settings.monthly === v ? 'on' : ''}" data-monthly="${v}">${v} €</button>`).join('')}</div>
      <p class="muted small">Réparti selon ta cible, plus fort sur ce qui est bon marché, rien sur ce qui est trop cher. Étalé sur ${pl.weeks} semaine${pl.weeks > 1 ? 's' : ''} restante${pl.weeks > 1 ? 's' : ''} ce mois-ci.</p>
      ${pl.notes.map(n => `<p class="note">${esc(n)}</p>`).join('')}
    </section>
    ${sells ? `<h2 class="sec">Ventes conseillées</h2><section class="list">${sells}</section>` : ''}
    <h2 class="sec">Achats</h2><section class="list">${items}</section>`;
}

// ---------- Bulletin ----------
function viewGrade() {
  const g = E.grade(S.load('log', []), st.prices);
  const rows = [...g.rows].reverse().map(r => {
    const st8 = r.status === 'pending' ? `<small class="muted">noté dans ${r.daysLeft} j</small>` :
      `<b class="${r.status === 'good' ? 'up' : 'down'}">${r.status === 'good' ? '✓ juste' : '✗ raté'} <small>${fmtPct(r.move, true)}</small></b>`;
    return `<div class="grade-row">${badge(r.symbol)}<div class="row-m"><div class="row-t"><b>${r.action === 'buy' ? 'Achat' : 'Vente'} ${esc(r.symbol)}</b>${st8}</div><div class="row-s">${new Date(r.t).toLocaleDateString('fr-BE')} · prix ${fmtPrice(r.price)}</div></div></div>`;
  }).join('');
  const score = g.score == null
    ? `<div class="score-empty">Pas encore de note</div><p class="muted">Chaque conseil est noté ${E.GRADE_DAYS} jours plus tard : un achat est juste si le prix a monté, une vente si le prix a baissé.</p>`
    : `<div class="score"><b>${g.good}</b><span>/ ${g.done}</span></div><p class="muted">conseils justes (${Math.round(g.score * 100)} %). Sous 50 %, Xplorer ne vaut pas mieux qu'une pièce : à toi d'en tirer les conclusions.</p>`;
  return `${topBar('Bulletin de notes')}<section class="card center">${score}</section>
    <section class="list">${rows || '<p class="muted pad">Les conseils apparaîtront ici dès qu\'Xplorer en donne.</p>'}</section>`;
}

// ---------- Réglages ----------
function viewSettings(d) {
  const c = S.creds();
  const s = st.settings;
  const sum = s.assets.reduce((t, a) => t + a.target, 0);
  const key = c ? `<div class="kv"><div><span>Clé</span><b class="mono">…${esc(c.name.slice(-12))}</b></div></div>
      ${st.cbError ? `<p class="note err">${esc(st.cbError)}</p>` : `<p class="note ok">Connectée en lecture seule.</p>`}
      <button class="btn ghost" data-act="del-key">Retirer la clé</button>`
    : `<p class="muted small">Dans Coinbase (Paramètres › API, ou portal.cdp.coinbase.com), crée une clé avec <b>uniquement la permission « View »</b> et l'algorithme <b>ECDSA</b>. Colle ici le contenu du fichier téléchargé. Ne la colle jamais dans un chat.</p>
      <form data-form="key" class="stack">
        <input name="name" placeholder="Nom de la clé (organizations/…/apiKeys/…)" autocomplete="off" autocapitalize="off">
        <textarea name="pem" rows="5" placeholder="Clé privée (-----BEGIN EC PRIVATE KEY-----…) ou tout le fichier JSON" autocomplete="off" autocapitalize="off" spellcheck="false"></textarea>
        <button class="btn">Connecter</button>
      </form>${st.cbError ? `<p class="note err">${esc(st.cbError)}</p>` : ''}`;
  const targets = s.assets.map(a => `<div class="tgt">${badge(a.symbol)}<span>${esc(a.name)}</span>
      <input type="number" min="0" max="100" step="1" inputmode="numeric" value="${Math.round(a.target * 100)}" data-target="${esc(a.symbol)}"><span>%</span>
      <button class="link" data-act="del-asset" data-sym="${esc(a.symbol)}" aria-label="Retirer">✕</button></div>`).join('');
  const results = st.searchResults ? st.searchResults.map(r => `<button class="row" data-act="add-asset" data-cg="${esc(r.cg)}" data-sym="${esc(r.symbol)}" data-name="${esc(r.name)}">${badge(r.symbol)}<div class="row-m"><div class="row-t"><b>${esc(r.name)}</b><span>${esc(r.symbol)}</span></div></div></button>`).join('') || '<p class="muted small">Aucun résultat.</p>' : '';
  return `${topBar('Réglages')}
    <section class="card"><h3>Coinbase (lecture seule)</h3>${key}</section>
    <section class="card"><h3>Répartition cible</h3>${targets}
      <p class="note ${Math.round(sum * 100) === 100 ? 'ok' : 'err'}">Total : ${Math.round(sum * 100)} %${Math.round(sum * 100) === 100 ? '' : ' — il faut arriver à 100 %'}</p>
      <form data-form="search" class="alert-form"><input name="q" placeholder="Ajouter une crypto (ex. SOL)" autocapitalize="off"><button class="btn sm">Chercher</button></form>
      ${results}
    </section>
    <section class="card"><h3>Règle GEOD</h3>
      <form data-form="geod" class="geod">
        <label>Ton prix d'achat<input name="base" type="number" step="any" inputmode="decimal" value="${s.geodBase}"></label>
        <label>Vendre à partir de x<input name="mult" type="number" step="1" inputmode="numeric" value="${s.geodMult}"></label>
        <button class="btn sm">OK</button>
      </form>
      <p class="muted small">Achat seulement. Vente proposée à partir de ${fmtPrice(s.geodBase * s.geodMult)}.</p>
    </section>
    <section class="card"><h3>Notifications</h3>
      <label class="switch"><input type="checkbox" data-act="notif" ${s.notifications ? 'checked' : ''}><span>Me prévenir quand un conseil change et pour mes alertes de prix</span></label>
      <button class="btn ghost" data-act="test-notif">Tester une notification</button>
      <p class="muted small">Pour l'instant, Xplorer vérifie quand l'app est ouverte ou revient au premier plan.</p>
    </section>
    <p class="foot">Xplorer ne passe jamais d'ordre et n'a pas accès aux retraits.</p>`;
}

// ================= interactions =================

$app.addEventListener('click', async ev => {
  const el = ev.target.closest('[data-go],[data-coin],[data-act],[data-range],[data-monthly]');
  if (!el) return;
  if (el.dataset.go) { st.view = el.dataset.go; st.sheet = null; render(); window.scrollTo(0, 0); return; }
  if (el.dataset.coin) { st.coin = el.dataset.coin; st.view = 'coin'; st.sheet = null; render(); window.scrollTo(0, 0); return; }
  if (el.dataset.range) { st.range = el.dataset.range; render(); return; }
  if (el.dataset.monthly) { st.settings.monthly = +el.dataset.monthly; S.saveSettings(st.settings); render(); return; }
  const act = el.dataset.act;
  if (act === 'refresh') refresh({ force: true });
  else if (act === 'todo') { st.sheet = 'todo'; render(); }
  else if (act === 'close') { st.sheet = null; render(); }
  else if (act === 'del-key') { if (confirm('Retirer la clé Coinbase de ce téléphone ?')) { S.clearCreds(); st.balances = null; st.fills = []; S.remove('balances'); S.remove('fills'); render(); } }
  else if (act === 'del-asset') { st.settings.assets = st.settings.assets.filter(a => a.symbol !== el.dataset.sym); S.saveSettings(st.settings); render(); }
  else if (act === 'add-asset') {
    if (!st.settings.assets.some(a => a.symbol === el.dataset.sym)) st.settings.assets.push({ symbol: el.dataset.sym, name: el.dataset.name, cg: el.dataset.cg, target: 0 });
    S.saveSettings(st.settings); st.searchResults = null; render(); refresh();
  }
  else if (act === 'del-alert') { st.settings.alerts = st.settings.alerts.filter(a => String(a.id) !== el.dataset.id); S.saveSettings(st.settings); render(); }
  else if (act === 'notif') { st.settings.notifications = el.checked; S.saveSettings(st.settings); if (el.checked) askNotifications(); }
  else if (act === 'test-notif') notify('Xplorer', 'Les notifications fonctionnent.');
});

$app.addEventListener('change', ev => {
  const sym = ev.target.dataset?.target;
  if (sym) {
    const a = st.settings.assets.find(x => x.symbol === sym);
    a.target = Math.max(0, Math.min(100, +ev.target.value || 0)) / 100;
    S.saveSettings(st.settings); render();
  }
});

$app.addEventListener('submit', async ev => {
  ev.preventDefault();
  const f = ev.target, kind = f.dataset.form, data = Object.fromEntries(new FormData(f));
  if (kind === 'monthly') { st.settings.monthly = Math.max(0, +data.monthly || 0); S.saveSettings(st.settings); render(); }
  else if (kind === 'alert') {
    const price = parseFloat(String(data.price).replace(',', '.'));
    if (price > 0) { st.settings.alerts.push({ id: Date.now(), symbol: f.dataset.sym, dir: data.dir, price, done: false }); S.saveSettings(st.settings); askNotifications(); render(); }
  }
  else if (kind === 'geod') {
    st.settings.geodBase = parseFloat(String(data.base).replace(',', '.')) || st.settings.geodBase;
    st.settings.geodMult = parseFloat(data.mult) || st.settings.geodMult;
    S.saveSettings(st.settings); render();
  }
  else if (kind === 'search') {
    try { st.searchResults = data.q ? await M.search(data.q) : null; } catch { st.searchResults = []; }
    render();
  }
  else if (kind === 'key') {
    try {
      const creds = CB.parseKeyInput(data.name, data.pem);
      st.cbError = null;
      S.saveCreds(creds);
      await CB.fetchBalances(creds); // test immédiat
      render(); refresh();
    } catch (e) { S.clearCreds(); st.cbError = e.message; render(); }
  }
});

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && (!st.lastUpdate || Date.now() - st.lastUpdate > 10 * 60000)) refresh();
});
setInterval(() => { if (document.visibilityState === 'visible') refresh(); }, 15 * 60000);
globalThis.Capacitor?.Plugins?.App?.addListener?.('backButton', () => {
  if (st.sheet) { st.sheet = null; render(); }
  else if (st.view !== 'home') { st.view = 'home'; render(); }
  else globalThis.Capacitor.Plugins.App.exitApp();
});

render();
refresh();
