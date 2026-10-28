// Historique réel reconstruit à partir des transactions Coinbase (données synthétiques)
import assert from 'node:assert/strict';
import { realHistory } from '../www/js/engine.js';
const D = 86400000, t0 = Date.now() - 20 * D;
const hist = { BTC: Array.from({ length: 20 }, (_, i) => ({ t: t0 + i * D, c: 100 + i * 5 })), GEOD: Array.from({ length: 20 }, (_, i) => ({ t: t0 + i * D, c: 1 })) };
const txs = [
  { t: t0 + 1 * D + 10, type: 'fiat_deposit', cur: 'EUR', amount: 500, eur: 500 },
  { t: t0 + 1 * D + 20, type: 'advanced_trade_fill', cur: 'EUR', amount: -300, eur: -300 },
  { t: t0 + 1 * D + 20, type: 'advanced_trade_fill', cur: 'BTC', amount: 300 / 105, eur: 300 },
  { t: t0 + 1 * D + 30, type: 'buy', cur: 'EUR', amount: -200, eur: -200 },
  { t: t0 + 1 * D + 30, type: 'buy', cur: 'GEOD', amount: 200, eur: 200 },
  { t: t0 + 10 * D, type: 'fiat_deposit', cur: 'EUR', amount: 500, eur: 500 },
  { t: t0 + 12 * D, type: 'staking_reward', cur: 'GEOD', amount: 10, eur: 10 },
].sort((a, b) => a.t - b.t);
const balancesNow = { EUR: 500, BTC: 300 / 105, GEOD: 210 };
const r = realHistory({ txs, balancesNow, history: hist, now: { BTC: 200, GEOD: 1 } });
assert.equal(r.investedNow, 1000, 'investi = les deux virements');
assert.equal(Math.round(r.mineNow), Math.round(500 + 300 / 105 * 200 + 210), 'finit au vrai total');
assert.ok(Math.abs(r.drift) < 1e-6, 'historique cohérent avec les soldes');
assert.equal(r.mine[0].v, 0, 'avant le premier virement : 0 €');
assert.ok(r.marketNow > 1000, 'les 1000 € en BTC ont monté');
// une transaction manquante se voit dans "drift"
const r2 = realHistory({ txs, balancesNow: { ...balancesNow, GEOD: 310 }, history: hist, now: { BTC: 200, GEOD: 1 } });
assert.equal(Math.round(r2.drift), 100);
console.log('Historique réel : tests passent ✓');
