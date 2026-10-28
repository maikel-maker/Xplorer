import * as E from './engine.js';
import * as M from './market.js';
import * as S from './store.js';
import * as CB from './coinbase.js';
import { lineChart, wireCharts, sparkline, gauge } from './chart.js';
import { technical } from './ta.js';
import { notify, askNotifications } from './native.js';

const { fmtEur, fmtQty, fmtPct, priceDecimals } = E;
const fmtPrice = p => fmtEur(p, priceDecimals(p ?? 0));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const $app = document.getElementById('app');

const st = {
  view: 'home', coin: null, range: '1M', perfRange: '3M', fib: false, fine: {}, sheet: null, counted: false,
  settings: S.settings(),
  balances: S.load('balances'),
  fills: S.load('fills', []),
  txs: S.load('txs', []), txError: null,
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
  const TRADE = ['buy', 'sell', 'advanced_trade_fill', 'trade'];
  const fillsEur = st.txs.length
    ? st.txs.filter(x => TRADE.includes(x.type) && !S.CASH.includes(x.cur) && x.eur)
        .map(x => ({ t: x.t, symbol: x.cur, side: x.amount > 0 ? 'BUY' : 'SELL', qty: Math.abs(x.amount), eurPrice: Math.abs(x.eur / x.amount) }))
    : st.fills.map(f => ({ ...f, eurPrice: fillEurPrice(f) }));
  const change24 = pf.rows.reduce((t, r) => { const c = st.prices[r.symbol]?.ch24; return c != null ? t + r.value - r.value / (1 + c) : t; }, 0);
  return { cash, holdings, analyses, pf, sells, plan, planBy, heads, todo, fillsEur, change24 };
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
        try {
          const wanted = new Set([...st.settings.assets.map(a => a.symbol), ...st.fills.map(f => f.symbol), 'USDC', 'EUR']);
          st.progress = 'Historique Coinbase…'; render();
          st.txs = await CB.fetchTransactions(creds, wanted, c => { st.progress = `Historique ${c}…`; });
          S.save('txs', st.txs); st.txError = null;
        } catch (e) { st.txError = e.message; }
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

const COLORS = { BTC: '#F7931A', ETH: '#8C9EFF', GEOD: '#4FD1E8', LINK: '#4C7DFF', TAO: '#C9D3E6', KRL: '#13B5EA', SOL: '#B57BFF' };
const badge = sym => `<span class="badge" style="--c:${COLORS[sym] || '#7D8BA6'}">${esc(sym.slice(0, 4))}</span>`;
const zoneChip = an => `<span class="chip z-${an?.zone?.key || 'none'}"><i class="z"></i>${an?.zone?.label || 'Pas assez de données'}</span>`;
const EMO = { '🔴': 'red', '🟢': 'green', '🟡': 'yellow', '🟠': 'orange', '⚪': 'none' };
function sentence(txt) {
  const first = [...(txt || '')][0];
  const z = EMO[first];
  return z ? `<i class="z zd-${z}"></i>${esc(txt.slice(first.length).trim())}` : esc(txt);
}

const ICONS = {
  home: '<path d="M4 11.5 12 5l8 6.5V20a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z"/>',
  plan: '<rect x="4" y="5" width="16" height="15" rx="3"/><path d="M4 10h16M9 3v4M15 3v4"/>',
  grade: '<path d="M12 3l2.6 5.6 6 .7-4.5 4.1 1.2 6L12 16.5 6.7 19.4l1.2-6L3.4 9.3l6-.7z"/>',
  settings: '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
  refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
};
const icon = n => `<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${ICONS[n]}</svg>`;
const LOGO = `<svg viewBox="0 0 32 32" aria-hidden="true"><defs><linearGradient id="lg1" x1="0" x2="1" y1="1" y2="0"><stop offset="0" stop-color="#2A8FC4"/><stop offset="1" stop-color="#7FE3F2"/></linearGradient></defs>
  <path d="M7 7l18 18" stroke="#E2B86B" stroke-width="4.2" stroke-linecap="round"/><path d="M7 25L25 7" stroke="url(#lg1)" stroke-width="4.2" stroke-linecap="round"/></svg>`;

function render() {
  try { renderInner(); }
  catch (e) { showCrash(e); }
}
function showCrash(e) {
  $app.innerHTML = `<main class="view"><header class="top"><div class="top-t"><h1>Xplorer</h1></div></header>
    <section class="card glass"><h3>Un écran n'a pas pu s'afficher</h3>
    <p class="muted small">Envoie une capture de ce message pour que ce soit corrigé.</p>
    <p class="note err mono">${esc(e?.message || e)}<br>${esc((e?.stack || '').split('\n').slice(0, 3).join(' | '))}</p>
    <button class="btn" data-act="reset-view">Revenir à l'accueil</button></section></main>`;
}
window.addEventListener('error', ev => { if ($app.querySelector('.boot')) showCrash(ev.error || ev.message); });
function renderInner() {
  const d = derive();
  let body;
  if (st.view === 'coin' && st.coin) body = viewCoin(d, st.coin);
  else if (st.view === 'plan') body = viewPlan(d);
  else if (st.view === 'grade') body = viewGrade();
  else if (st.view === 'settings') body = viewSettings();
  else body = viewHome(d);
  const nav = st.view === 'coin' ? '' : `<nav class="tabs">${[
    ['home', 'Accueil'], ['plan', 'Plan'], ['grade', 'Bulletin'], ['settings', 'Réglages'],
  ].map(([v, l]) => `<button data-go="${v}" class="${st.view === v ? 'on' : ''}">${icon(v)}<span>${l}</span></button>`).join('')}</nav>`;
  $app.innerHTML = `<main class="view">${body}</main>${nav}${st.sheet === 'todo' ? sheetTodo(d) : ''}`;
  wireCharts($app, v => fmtEur(v, priceDecimals(v)));
  countUp();
}

// Le total s'anime une seule fois, à l'ouverture
function countUp() {
  const el = $app.querySelector('[data-count]');
  if (!el || st.counted) return;
  const target = +el.dataset.count;
  if (!(target > 0) || matchMedia('(prefers-reduced-motion: reduce)').matches) { st.counted = true; return; }
  st.counted = true;
  const t0 = performance.now(), dur = 900;
  const step = now => {
    const k = Math.min(1, (now - t0) / dur), e = 1 - Math.pow(1 - k, 3);
    if (!el.isConnected) return;
    el.innerHTML = totalHtml(target * e);
    if (k < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}
function totalHtml(v) {
  const [i, c] = fmtEur(v, 2).replace(/\s€$/, '').split(',');
  return `${i}<span class="cents">,${c} €</span>`;
}

function topBar(title, { back = false, brand = false } = {}) {
  const upd = st.loading ? esc(st.progress) : st.lastUpdate ? `Mis à jour à ${new Date(st.lastUpdate).toLocaleTimeString('fr-BE', { hour: '2-digit', minute: '2-digit' })}` : '';
  return `<header class="top">
    ${back ? `<button class="icon-btn" data-go="home" aria-label="Retour">${icon('back')}</button>` : ''}
    <div class="top-t">${brand ? `<div class="brand">${LOGO}<h1>${title}</h1></div>` : `<h1>${title}</h1>`}<small>${upd}</small></div>
    <button class="icon-btn ${st.loading ? 'spin' : ''}" data-act="refresh" aria-label="Actualiser">${icon('refresh')}</button>
  </header>${st.error ? `<div class="banner err">${esc(st.error)}</div>` : ''}`;
}

const seg = (opts, cur, attr, cls = '') => `<div class="seg ${cls}">${opts.map(o => `<button data-${attr}="${o}" class="${cur === o ? 'on' : ''}">${o}</button>`).join('')}</div>`;
const cutFor = k => Date.now() - ({ '1J': 1, '1S': 7, '1M': 31, '3M': 92, '1A': 365, 'Tout': 1e5 }[k]) * 864e5;

// ---------- Accueil ----------
function viewHome(d) {
  const hasKey = !!S.creds();
  const total = d.pf.total + (d.cash || 0);
  let hero;
  if (!hasKey) {
    hero = `<section class="hero glass">
      <div class="lbl">Ton portefeuille</div>
      <p class="hero-empty">Branche ta clé Coinbase en lecture seule pour voir ton portefeuille, tes achats sur les graphiques et ta courbe face au marché.</p>
      <button class="btn" data-go="settings">Ajouter ma clé Coinbase</button>
    </section>`;
  } else {
    const ch = d.change24;
    const nowPx = Object.fromEntries(Object.entries(st.prices).map(([k, v]) => [k, v.eur]));
    const usdEur = st.prices.USDC?.eur ?? 0.92;
    const perf = st.txs.length ? E.realHistory({ txs: st.txs, balancesNow: st.balances, history: st.hist, now: nowPx, usdEur, startT: cutFor(st.perfRange) }) : null;
    st.autoInvested = perf ? perf.investedNow : null;
    const manual = st.settings.investedManual > 0 ? st.settings.investedManual : null;
    if (perf && manual) perf.investedNow = manual;
    const periods = { '1M': 'Sur 1 mois', '3M': 'Sur 3 mois', '1A': 'Sur 1 an', 'Tout': 'Depuis ton premier dépôt' };
    let perfBlock = st.txError ? `<p class="note err" style="margin-top:14px">${esc(st.txError)}</p>` : (!perf ? `<p class="muted small" style="margin-top:14px">Ton historique Coinbase se charge à la prochaine actualisation.</p>` : '');
    if (perf) {
      const gain = perf.mineNow - perf.investedNow;
      const gainPct = perf.investedNow > 0 ? gain / perf.investedNow : null;
      const diff = perf.mineNow - perf.marketNow;
      const tie = Math.abs(diff) < Math.max(1, perf.mineNow * 0.002);
      const gap = Math.abs(perf.drift) > Math.max(5, perf.mineNow * 0.02);
      perfBlock = `<div class="stats">
          <div><span>Argent investi</span><b>${fmtEur(perf.investedNow, 0)}</b></div>
          <div><span>Valeur aujourd'hui</span><b>${fmtEur(perf.mineNow, 0)}</b></div>
          <div><span>Gain ou perte</span><b class="${gain >= 0 ? 'up' : 'down'}">${gain >= 0 ? '+' : '−'}${fmtEur(Math.abs(gain), 0)}${gainPct != null ? ` <small>${fmtPct(gainPct, true)}</small>` : ''}</b></div>
        </div>
        <div class="vs">
        <div class="vs-head"><h2>Toi face au marché</h2>${seg(['1M', '3M', '1A', 'Tout'], st.perfRange, 'perf', 'sm')}</div>
        ${lineChart({ height: 160, label: 'Toi face au marché', series: [
          { name: 'Toi', points: perf.mine, color: 'var(--cyan)', fill: true },
          { name: 'Tout en BTC', points: perf.market, color: 'var(--gold)', width: 1.6 },
          ...(manual ? [] : [{ name: 'Investi', points: perf.invested, color: 'var(--slate)', width: 1.4, dash: '4 4' }]),
        ] })}
        <div class="legend"><span><i style="background:var(--cyan)"></i>Ton portefeuille</span><span><i style="background:var(--gold)"></i>Même argent en BTC</span>${manual ? '' : '<span><i style="background:var(--slate)"></i>Argent investi</span>'}</div>
        <p class="verdict ${diff >= 0 ? 'up' : 'down'}">${periods[st.perfRange]}, ${tie ? 'tu fais jeu égal avec le BTC.' : `${diff >= 0 ? 'tu fais mieux que le BTC de' : 'le BTC fait mieux que toi de'} <b>${fmtEur(Math.abs(diff))}</b>.`}</p>
        <p class="muted small">${manual ? `Argent investi : ${fmtEur(manual, 0)}, le montant que tu as indiqué dans Réglages.` : `Argent investi : tes virements en euros vers Coinbase moins tes retraits. Si ce n'est pas le bon montant, corrige-le dans Réglages.`}</p>
        ${gap ? `<p class="note err">Il manque environ ${fmtEur(Math.abs(perf.drift), 0)} dans l'historique Coinbase lu par l'app : la courbe passée peut être un peu fausse.</p>` : ''}
      </div>`;
    }
    hero = `<section class="hero glass">
      <div class="lbl">Ton portefeuille</div>
      <div class="total" data-count="${total}">${totalHtml(st.counted ? total : 0)}</div>
      <div class="facts">
        <span class="fact ${ch >= 0 ? 'up' : 'down'}"><b>${ch >= 0 ? '+' : '−'}${fmtEur(Math.abs(ch))}</b> en 24 h</span>
        <span class="fact">Cryptos <b>${fmtEur(d.pf.total, 0)}</b></span>
        <span class="fact">Cash <b>${fmtEur(d.cash || 0, 0)}</b></span>
      </div>
      ${st.cbError ? `<div class="banner err" style="margin-top:12px">${esc(st.cbError)}</div>` : ''}
      ${perfBlock}
    </section>`;
  }
  const n = d.todo.length;
  const pill = `<button class="todo ${n ? 'has' : ''}" data-act="todo">
    <span class="pulse"></span><span class="t">${n ? 'À faire aujourd\'hui' : 'Rien à faire aujourd\'hui'}</span>${n ? `<span class="count">${n}</span>` : ''}
  </button>`;
  const rows = d.pf.rows.filter(r => r.target > 0 || r.qty > 0).sort((a, b) => b.target - a.target || b.value - a.value).map(r => {
    const p = st.prices[r.symbol];
    const h = (st.hist[r.symbol] || []).slice(-30).map(x => x.c);
    if (p?.eur) h.push(p.eur);
    const upTrend = h.length > 1 && h[h.length - 1] >= h[0];
    return `<button class="row" data-coin="${esc(r.symbol)}">
      ${badge(r.symbol)}
      <div class="row-m">
        <div class="row-t"><b>${esc(r.name)}</b><span>${hasKey ? fmtEur(r.value) : fmtPrice(p?.eur)}</span></div>
        <div class="row-s">${sentence(d.heads[r.symbol])}</div>
      </div>
      ${sparkline(h, upTrend ? 'var(--gain)' : 'var(--loss)')}
    </button>`;
  }).join('');
  return `${topBar('Xplorer', { brand: true })}${hero}${pill}
    <h2 class="sec">Tes cryptos</h2><section class="list glass">${rows}</section>
    <p class="foot">Xplorer conseille, il ne passe jamais d'ordre. Ce sont des calculs, pas des garanties.</p>`;
}

function sheetTodo(d) {
  const items = d.todo.map(t => `<button class="row" data-coin="${esc(t.symbol)}">${badge(t.symbol)}<div class="row-m"><div class="row-t"><b>${esc(t.symbol)}</b><span class="${t.action === 'sell' ? 'down' : 'up'}">${t.action === 'sell' ? 'Vendre' : 'Acheter'}</span></div><div class="row-s">${sentence(t.text)}</div></div></button>`).join('');
  return `<div class="scrim" data-act="close"></div><div class="sheet"><div class="grab"></div><h2>À faire aujourd'hui</h2>
    ${items || '<p class="muted">Rien à faire : on laisse le marché venir à nous.</p>'}
    <p class="muted small">Tu passes les ordres toi-même sur Coinbase Advanced.</p></div>`;
}

// ---------- Fiche crypto ----------
function chartPoints(sym) {
  const p = st.prices[sym]?.eur;
  if (st.range === '1J' || st.range === '1S') {
    const key = `${sym}.${st.range}`;
    const data = st.fine[key];
    if (!data) { loadFine(sym, st.range); return null; }
    return data.map(x => ({ t: x.t, v: x.c }));
  }
  const cut = cutFor(st.range);
  return [...(st.hist[sym] || []).filter(x => x.t >= cut).map(x => ({ t: x.t, v: x.c })), ...(p ? [{ t: Date.now(), v: p }] : [])];
}
const pendingFine = new Set();
async function loadFine(sym, range) {
  const key = `${sym}.${range}`;
  if (pendingFine.has(key)) return;
  pendingFine.add(key);
  const a = st.settings.assets.find(x => x.symbol === sym);
  try { st.fine[key] = a ? await M.intraday(a, range === '1J' ? 1 : 7) : []; } catch { st.fine[key] = []; }
  pendingFine.delete(key);
  if (st.view === 'coin' && st.coin === sym && st.range === range) render();
}

function viewCoin(d, sym) {
  const a = st.settings.assets.find(x => x.symbol === sym);
  const r = d.pf.rows.find(x => x.symbol === sym);
  const an = d.analyses[sym];
  const p = st.prices[sym];
  const closes = (st.hist[sym] || []).map(x => x.c);
  const ta = technical(closes, p?.eur);
  const pts = chartPoints(sym);
  const fills = d.fillsEur.filter(f => f.symbol === sym && f.eurPrice > 0);
  const geodExit = st.settings.geodBase * st.settings.geodMult;

  let chart;
  if (!pts) chart = `<div class="chart-load">Chargement des prix…</div>`;
  else {
    const lines = [];
    const vmax = Math.max(...pts.map(x => x.v), 0), vmin = Math.min(...pts.map(x => x.v));
    const visible = v => v >= vmin * 0.8 && v <= vmax * 1.25;
    if (st.fib && ta?.fib) {
      for (const l of ta.fib.levels) if (visible(l.v)) lines.push({ v: l.v, color: 'var(--gold)', label: `${(l.r * 100).toLocaleString('fr-BE', { maximumFractionDigits: 1 })} %  ${fmtPrice(l.v)}` });
    } else if (an?.enough && !['1J', '1S'].includes(st.range)) {
      if (visible(an.thresholds.buyBelow)) lines.push({ v: an.thresholds.buyBelow, color: 'var(--gain)', label: `Bon achat sous ${fmtPrice(an.thresholds.buyBelow)}` });
      if (sym !== 'GEOD' && visible(an.thresholds.sellAbove)) lines.push({ v: an.thresholds.sellAbove, color: 'var(--loss)', label: `Vente au-dessus de ${fmtPrice(an.thresholds.sellAbove)}` });
      if (sym === 'GEOD' && visible(geodExit)) lines.push({ v: geodExit, color: 'var(--loss)', label: `Ta sortie x${st.settings.geodMult} : ${fmtPrice(geodExit)}` });
    }
    chart = lineChart({
      height: 230, label: `Prix ${sym}`,
      series: [{ name: sym, points: pts, color: 'var(--cyan)', fill: true }],
      markers: fills.map(f => ({ t: f.t, v: f.eurPrice, kind: f.side === 'BUY' ? 'buy' : 'sell' })),
      lines,
    });
  }

  // conseil
  const sell = d.sells[sym], pl = d.planBy[sym];
  let advice;
  if (!an?.enough) advice = `<p>Pas assez d'historique de prix pour donner un avis honnête. Xplorer préfère se taire que deviner.</p>`;
  else if (sell?.decide) advice = `<div class="act sell"><b>Vendre</b><span>Tu décides combien</span></div><p>${esc(sell.why)}</p>`;
  else if (sell) advice = `<div class="act sell"><b>Vends ${fmtEur(sell.eur)}</b><span>≈ ${fmtQty(sell.qty)} ${esc(sym)}</span></div>
      <dl class="reason"><dt>Pourquoi</dt><dd>${esc(sell.why)}</dd><dt>Ensuite</dt><dd>${esc(sell.after)}</dd></dl>`;
  else if (pl && pl.eur > 0) advice = `<div class="act buy"><b>Achète ${fmtEur(pl.weekEur)}</b><span>cette semaine<br>${fmtEur(pl.eur)} sur le mois</span></div>
      <dl class="reason"><dt>Combien</dt><dd>≈ ${fmtQty(pl.weekEur / (p?.eur || 1))} ${esc(sym)} cette semaine</dd><dt>Pourquoi</dt><dd>${esc(pl.why)}</dd></dl>`;
  else if (pl) advice = `<div class="act wait"><b>On attend</b></div><p>${esc(pl.why)}</p>`;
  else advice = `<p>${sentence(d.heads[sym] || '')}${r?.target === 0 ? ' Cette crypto n\'a pas de cible dans ta répartition : Xplorer ne propose pas d\'achat.' : ''}</p>`;
  if (sym === 'GEOD' && !sell) advice += `<p class="muted small">Règle GEOD : achat seulement. Vente proposée à partir de ${fmtPrice(geodExit)}, soit x${st.settings.geodMult} depuis ${fmtPrice(st.settings.geodBase)}.</p>`;

  // baromètre technique
  const word = s => s >= 0.35 ? 'Plutôt positif' : s <= -0.35 ? 'Plutôt négatif' : 'Neutre';
  const stLbl = { pos: 'Positif', neg: 'Négatif', neu: 'Neutre' };
  const taCard = ta ? `<section class="card glass"><h3>Baromètre technique</h3>
      <div class="gauge">${gauge(ta.score)}<div class="gauge-t">${word(ta.score)}</div></div>
      <p class="gauge-s">${esc(ta.summary)}</p>
      <div class="tally"><span class="t-pos">${ta.pos} positif${ta.pos > 1 ? 's' : ''}</span><span class="t-neg">${ta.neg} négatif${ta.neg > 1 ? 's' : ''}</span><span class="t-neu">${ta.neu} neutre${ta.neu > 1 ? 's' : ''}</span></div>
      ${ta.items.map(i => `<div class="ta"><div class="ta-h"><b>${esc(i.name)}<span class="st ${i.status}">${stLbl[i.status]}</span></b><span class="num">${esc(String(i.value))}</span></div><p>${esc(i.text)}</p></div>`).join('')}
    </section>` : '';

  // pourquoi ce feu
  const why = an?.enough ? `<section class="card glass"><h3>Pourquoi ce feu</h3>
    ${['buy', 'sell'].map(side => `<div class="sub-h">${side === 'buy' ? 'Conditions d\'achat' : 'Conditions de vente'}</div>` +
      an.indicators.filter(i => i.side === side).map(i => `<div class="rule ${i.ok ? 'ok' : ''}"><span class="tick">${i.ok ? '✓' : '–'}</span><span>${esc(i.label)}<small>${esc(i.value)}</small></span></div>`).join('')).join('')}
    </section>` : '';

  // position
  let pos = '';
  if (r && r.qty > 0) {
    const buys = fills.filter(f => f.side === 'BUY');
    const bq = buys.reduce((t, f) => t + f.qty, 0);
    const avg = bq > 0 ? buys.reduce((t, f) => t + f.qty * f.eurPrice, 0) / bq : null;
    const gain = avg ? r.value - avg * r.qty : null;
    const w = Math.min(100, r.weight * 100), tg = Math.min(100, r.target * 100);
    pos = `<section class="card glass"><h3>Ta position</h3><div class="kv">
      <div><span>Quantité</span><b>${fmtQty(r.qty)} ${esc(sym)}</b></div>
      <div><span>Valeur</span><b>${fmtEur(r.value)}</b></div>
      <div style="display:block"><div style="display:flex;justify-content:space-between"><span>Part du portefeuille</span><b>${Math.round(w)} % <small>pour ${Math.round(tg)} % visés</small></b></div>
        <div class="bar"><i style="width:${w}%"></i><u style="left:${tg}%"></u></div></div>
      <div><span>Prix moyen d'achat</span><b>${avg ? fmtPrice(avg) : '—'}</b></div>
      ${gain != null ? `<div><span>Gain ou perte</span><b class="${gain >= 0 ? 'up' : 'down'}">${gain >= 0 ? '+' : '−'}${fmtEur(Math.abs(gain))} (${fmtPct(gain / (avg * r.qty), true)})</b></div>` : ''}
    </div>${avg ? '' : '<p class="muted small">Le prix moyen se calcule à partir de tes achats Coinbase. Les cryptos reçues autrement ne sont pas comptées.</p>'}</section>`;
  }

  const alerts = st.settings.alerts.filter(x => x.symbol === sym);
  const alertCard = `<section class="card glass"><h3>Alerte de prix</h3>
    ${alerts.map(al => `<div class="alert-row ${al.done ? 'done' : ''}"><span>${al.dir === 'above' ? 'Au-dessus de' : 'En dessous de'} ${fmtPrice(al.price)}${al.done ? ', déclenchée' : ''}</span><button class="link" data-act="del-alert" data-id="${al.id}">Supprimer</button></div>`).join('')}
    <form class="alert-form" data-form="alert" data-sym="${esc(sym)}">
      <select name="dir" class="field"><option value="below">En dessous de</option><option value="above">Au-dessus de</option></select>
      <input name="price" type="number" step="any" inputmode="decimal" placeholder="Prix en €" required>
      <button class="btn sm">Ajouter</button>
    </form></section>`;

  const hist = fills.length ? `<section class="card glass"><h3>Tes achats et ventes</h3>${[...fills].reverse().slice(0, 30).map(f => `
    <div class="fill"><span class="dotc ${f.side === 'BUY' ? 'buy' : 'sell'}"></span><span>${new Date(f.t).toLocaleDateString('fr-BE')}</span><span>${f.side === 'BUY' ? 'Achat' : 'Vente'} de ${fmtQty(f.qty)}</span><b>${fmtPrice(f.eurPrice)}</b></div>`).join('')}</section>` : '';

  return `${topBar(esc(a?.name || sym), { back: true })}
    <section class="coin-head">
      <div><div class="price">${fmtPrice(p?.eur)}</div>
      ${p?.ch24 != null ? `<div class="chg ${p.ch24 >= 0 ? 'up' : 'down'}">${fmtPct(p.ch24, true)} en 24 h</div>` : ''}</div>
      ${zoneChip(an)}
    </section>
    <div class="tools">${seg(['1J', '1S', '1M', '1A', 'Tout'], st.range, 'range')}${ta?.fib ? `<button class="toggle ${st.fib ? 'on' : ''}" data-act="fib">Fibo</button>` : ''}</div>
    ${chart}
    <div class="legend"><span><i style="background:var(--gain)"></i>Tes achats</span><span><i style="background:var(--loss)"></i>Tes ventes</span>${st.fib ? '<span><i style="background:var(--gold)"></i>Niveaux de Fibonacci</span>' : ''}</div>
    <section class="card glass"><h3>Ce qu'il faut faire</h3>${advice}</section>
    ${taCard}${pos}${why}${alertCard}${hist}`;
}

// ---------- Plan du mois ----------
function viewPlan(d) {
  const pl = d.plan;
  const month = new Date().toLocaleDateString('fr-BE', { month: 'long' });
  const sells = Object.entries(d.sells).map(([sym, s]) => `<button class="row" data-coin="${esc(sym)}">${badge(sym)}<div class="row-m"><div class="row-t"><b>${esc(sym)}</b><span class="down">${s.decide ? 'À décider' : `Vendre ${fmtEur(s.eur)}`}</span></div><div class="row-s">${esc(s.why)}</div></div></button>`).join('');
  const items = pl.items.map(i => `<button class="row" data-coin="${esc(i.symbol)}">
    ${badge(i.symbol)}
    <div class="row-m">
      <div class="row-t"><b>${esc(i.name)}</b><span class="${i.eur > 0 ? 'up' : 'muted'}">${i.eur > 0 ? `${fmtEur(i.weekEur)} cette sem.` : '0 €'}</span></div>
      <div class="row-s">${i.eur > 0 ? `${fmtEur(i.eur)} sur le mois, soit ≈ ${fmtQty(i.qty)} ${esc(i.symbol)}. ` : ''}${esc(i.why)}</div>
    </div>
  </button>`).join('');
  return `${topBar('Plan du mois')}
    <section class="card glass">
      <h3>Ton versement de ${esc(month)}</h3>
      <form class="amount" data-form="monthly">
        <input name="monthly" type="number" min="0" step="10" inputmode="numeric" value="${st.settings.monthly}" aria-label="Montant du mois">
        <span>€</span><button class="btn sm">Valider</button>
      </form>
      <div class="chips-row">${[500, 600, 750, 1000].map(v => `<button class="pill ${st.settings.monthly === v ? 'on' : ''}" data-monthly="${v}">${v} €</button>`).join('')}</div>
      <p class="muted small">Réparti selon ta cible, plus fort sur ce qui est bon marché, rien sur ce qui est trop cher. Étalé sur ${pl.weeks} semaine${pl.weeks > 1 ? 's' : ''} restante${pl.weeks > 1 ? 's' : ''} ce mois-ci.</p>
      ${pl.notes.map(n => `<p class="note">${esc(n)}</p>`).join('')}
    </section>
    ${sells ? `<h2 class="sec">Ventes conseillées</h2><section class="list glass">${sells}</section>` : ''}
    <h2 class="sec">Achats</h2><section class="list glass">${items}</section>`;
}

// ---------- Bulletin ----------
function viewGrade() {
  const g = E.grade(S.load('log', []), st.prices);
  const rows = [...g.rows].reverse().map(r => {
    const res = r.status === 'pending' ? `<small class="muted">noté dans ${r.daysLeft} j</small>` :
      `<b class="${r.status === 'good' ? 'up' : 'down'}">${r.status === 'good' ? '✓ juste' : '✗ raté'} <small>${fmtPct(r.move, true)}</small></b>`;
    return `<div class="grade-row">${badge(r.symbol)}<div class="row-m"><div class="row-t"><b>${r.action === 'buy' ? 'Achat' : 'Vente'} ${esc(r.symbol)}</b>${res}</div><div class="row-s">Le ${new Date(r.t).toLocaleDateString('fr-BE')}, au prix de ${fmtPrice(r.price)}</div></div></div>`;
  }).join('');
  const score = g.score == null
    ? `<div class="score-empty">Pas encore de note</div><p class="muted">Chaque conseil est noté ${E.GRADE_DAYS} jours plus tard : un achat est juste si le prix a monté, une vente si le prix a baissé.</p>`
    : `<div class="score">${g.good}<span> / ${g.done}</span></div><p class="muted">conseils justes, soit ${Math.round(g.score * 100)} %. Sous 50 %, Xplorer ne fait pas mieux qu'une pièce jetée en l'air.</p>`;
  return `${topBar('Bulletin de notes')}<section class="card glass center">${score}</section>
    <h2 class="sec">Historique des conseils</h2>
    <section class="list glass">${rows || '<p class="muted pad">Les conseils apparaîtront ici dès qu\'Xplorer en donne.</p>'}</section>`;
}

// ---------- Réglages ----------
function viewSettings() {
  const c = S.creds();
  const s = st.settings;
  const sum = s.assets.reduce((t, a) => t + a.target, 0);
  const key = c ? `<div class="kv"><div><span>Clé</span><b class="mono">…${esc(c.name.slice(-12))}</b></div></div>
      ${st.cbError ? `<p class="note err">${esc(st.cbError)}</p>` : `<p class="note ok">Connectée en lecture seule.</p>`}
      <button class="btn ghost" data-act="del-key">Retirer la clé</button>`
    : `<p class="muted small">Crée la clé sur portal.cdp.coinbase.com avec uniquement la permission View et l'algorithme ECDSA, puis colle le nom et la clé privée ici. Ne la colle jamais dans un chat.</p>
      <form data-form="key" class="stack">
        <input name="name" placeholder="Nom de la clé (organizations/…/apiKeys/…)" autocomplete="off" autocapitalize="off">
        <textarea name="pem" rows="5" placeholder="Clé privée : -----BEGIN EC PRIVATE KEY----- …" autocomplete="off" autocapitalize="off" spellcheck="false"></textarea>
        <button class="btn">Connecter</button>
      </form>${st.cbError ? `<p class="note err">${esc(st.cbError)}</p>` : ''}`;
  const targets = s.assets.map(a => `<div class="tgt">${badge(a.symbol)}<span>${esc(a.name)}</span>
      <input type="number" min="0" max="100" step="1" inputmode="numeric" value="${Math.round(a.target * 100)}" data-target="${esc(a.symbol)}" aria-label="Cible ${esc(a.name)}"><span>%</span>
      <button class="link" data-act="del-asset" data-sym="${esc(a.symbol)}" aria-label="Retirer ${esc(a.name)}">✕</button></div>`).join('');
  const results = st.searchResults ? st.searchResults.map(r => `<button class="row" data-act="add-asset" data-cg="${esc(r.cg)}" data-sym="${esc(r.symbol)}" data-name="${esc(r.name)}">${badge(r.symbol)}<div class="row-m"><div class="row-t"><b>${esc(r.name)}</b><span>${esc(r.symbol)}</span></div></div></button>`).join('') || '<p class="muted small">Aucun résultat.</p>' : '';
  const ok = Math.round(sum * 100) === 100;
  return `${topBar('Réglages')}
    <section class="card glass"><h3>Coinbase en lecture seule</h3>${key}</section>
    <section class="card glass"><h3>Répartition cible</h3>${targets}
      <p class="note ${ok ? 'ok' : 'err'}">Total : ${Math.round(sum * 100)} %${ok ? '' : '. Il faut arriver à 100 %.'}</p>
      <form data-form="search" class="alert-form"><input name="q" placeholder="Ajouter une crypto, par ex. SOL" autocapitalize="off"><button class="btn sm">Chercher</button></form>
      ${results}
    </section>
    <section class="card glass"><h3>Argent investi</h3>
      <p class="muted small">Xplorer additionne tes virements en euros vers Coinbase${st.autoInvested != null ? ` : il trouve <b>${fmtEur(st.autoInvested, 0)}</b>` : ''}. Si ce n'est pas juste, indique le vrai montant. Mets 0 pour revenir au calcul automatique.</p>
      <form class="amount" data-form="manual">
        <input name="manual" type="number" min="0" step="1" inputmode="decimal" value="${s.investedManual || ''}" placeholder="Ex. 700" aria-label="Argent investi">
        <span>€</span><button class="btn sm">Valider</button>
      </form>
    </section>
    <section class="card glass"><h3>Ce qu'Xplorer a lu chez Coinbase</h3>
      ${st.txs.length ? `<p class="muted small">${st.txs.length} opérations. Si un chiffre te paraît faux, envoie une capture de ce tableau.</p>
      <div class="txs">${E.txSummary(st.txs).slice(0, 25).map(r => `<div><span>${esc(r.type)} <small>${esc(r.cur)} × ${r.n}</small></span><b class="${r.eur >= 0 ? 'up' : 'down'}">${r.eur >= 0 ? '+' : '−'}${fmtEur(Math.abs(r.eur), 0)}</b></div>`).join('')}</div>`
      : `<p class="muted small">${st.txError ? esc(st.txError) : 'Rien de lu pour l\'instant. Actualise depuis l\'accueil.'}</p>`}
    </section>
    <section class="card glass"><h3>Règle GEOD</h3>
      <form data-form="geod" class="geod">
        <label>Ton prix d'achat<input name="base" type="number" step="any" inputmode="decimal" value="${s.geodBase}"></label>
        <label>Vendre à partir de x<input name="mult" type="number" step="1" inputmode="numeric" value="${s.geodMult}"></label>
        <button class="btn sm">Valider</button>
      </form>
      <p class="muted small">Achat seulement. Vente proposée à partir de ${fmtPrice(s.geodBase * s.geodMult)}.</p>
    </section>
    <section class="card glass"><h3>Notifications</h3>
      <label class="switch"><input type="checkbox" data-act="notif" ${s.notifications ? 'checked' : ''}><span>Me prévenir quand un conseil change et pour mes alertes de prix</span></label>
      <button class="btn ghost" data-act="test-notif">Tester une notification</button>
      <p class="muted small">Pour l'instant, Xplorer vérifie quand l'app est ouverte ou revient au premier plan.</p>
    </section>
    <p class="foot">Xplorer ne passe jamais d'ordre et n'a pas accès aux retraits.</p>`;
}

// ================= interactions =================

$app.addEventListener('click', async ev => {
  const el = ev.target.closest('[data-go],[data-coin],[data-act],[data-range],[data-monthly],[data-perf]');
  if (!el) return;
  if (el.dataset.go) { st.view = el.dataset.go; st.sheet = null; render(); window.scrollTo(0, 0); return; }
  if (el.dataset.coin) { st.coin = el.dataset.coin; st.view = 'coin'; st.sheet = null; render(); window.scrollTo(0, 0); return; }
  if (el.dataset.range) { st.range = el.dataset.range; render(); return; }
  if (el.dataset.perf) { st.perfRange = el.dataset.perf; render(); return; }
  if (el.dataset.monthly) { st.settings.monthly = +el.dataset.monthly; S.saveSettings(st.settings); render(); return; }
  const act = el.dataset.act;
  if (act === 'refresh') { st.fine = {}; refresh({ force: true }); }
  else if (act === 'reset-view') { st.view = 'home'; st.sheet = null; render(); }
  else if (act === 'fib') { st.fib = !st.fib; render(); }
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
  else if (kind === 'manual') { st.settings.investedManual = Math.max(0, parseFloat(String(data.manual).replace(',', '.')) || 0); S.saveSettings(st.settings); render(); }
  else if (kind === 'base') { st.settings.investedBase = Math.max(0, parseFloat(String(data.base).replace(',', '.')) || 0); S.saveSettings(st.settings); render(); }
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
