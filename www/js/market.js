// Prix publics : CoinGecko en priorité, Coinbase Exchange en secours.
import { load, save } from './store.js';

const CG = 'https://api.coingecko.com/api/v3';
const CBX = 'https://api.exchange.coinbase.com';
const DAY = 86400000;
const today = () => new Date().toISOString().slice(0, 10);
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function json(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

// Historique journalier en euros : [{ t, c }] (anciennes -> récentes, sans le jour en cours)
export async function history(asset, { force = false } = {}) {
  const key = `hist.${asset.symbol}`;
  const cached = load(key);
  if (!force && cached?.day === today() && cached.data?.length) return cached.data;
  let data = null;
  if (asset.cg) {
    try {
      const j = await json(`${CG}/coins/${encodeURIComponent(asset.cg)}/market_chart?vs_currency=eur&days=400&interval=daily`);
      const pts = (j.prices || []).map(([t, c]) => ({ t, c }));
      const startToday = new Date(new Date().toISOString().slice(0, 10)).getTime();
      data = dedupeDays(pts.filter(p => p.t < startToday));
    } catch { data = null; }
  }
  if (!data?.length) {
    try {
      const rows = await json(`${CBX}/products/${asset.symbol}-EUR/candles?granularity=86400`);
      data = rows.map(r => ({ t: r[0] * 1000, c: r[4] })).reverse().slice(0, -1);
    } catch { data = null; }
  }
  if (data?.length) { save(key, { day: today(), data }); return data; }
  return cached?.data || [];
}

function dedupeDays(pts) {
  const m = new Map();
  for (const p of pts) m.set(Math.floor(p.t / DAY), p);
  return [...m.values()].sort((a, b) => a.t - b.t);
}

// Prix actuels : { SYM: { eur, ch24 } }
export async function prices(assets) {
  const out = {};
  const withCg = assets.filter(a => a.cg);
  try {
    const ids = [...new Set(withCg.map(a => a.cg)), 'usd-coin'].join(',');
    const j = await json(`${CG}/simple/price?ids=${ids}&vs_currencies=eur&include_24hr_change=true`);
    for (const a of withCg) {
      const p = j[a.cg];
      if (p?.eur > 0) out[a.symbol] = { eur: p.eur, ch24: p.eur_24h_change != null ? p.eur_24h_change / 100 : null };
    }
    if (j['usd-coin']?.eur) out.USDC = { eur: j['usd-coin'].eur };
  } catch { /* secours ci-dessous */ }
  for (const a of assets) {
    if (out[a.symbol]) continue;
    try {
      const t = await json(`${CBX}/products/${a.symbol}-EUR/ticker`);
      const p = parseFloat(t.price);
      if (p > 0) out[a.symbol] = { eur: p, ch24: null };
    } catch { /* pas de prix = pas d'avis */ }
  }
  return out;
}

// Charge l'historique de toutes les cryptos sans se faire bloquer par la limite de CoinGecko
export async function histories(assets, onProgress) {
  const out = {};
  for (const a of assets) {
    const cached = load(`hist.${a.symbol}`);
    const fresh = cached?.day === today();
    out[a.symbol] = await history(a);
    onProgress?.(a.symbol);
    if (!fresh) await sleep(1300);
  }
  return out;
}

export async function search(q) {
  const j = await json(`${CG}/search?query=${encodeURIComponent(q)}`);
  return (j.coins || []).slice(0, 8).map(c => ({ cg: c.id, symbol: (c.symbol || '').toUpperCase(), name: c.name, thumb: c.thumb }));
}
