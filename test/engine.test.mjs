// Tests du moteur (données synthétiques, uniquement pour les tests)
import assert from 'node:assert/strict';
import * as E from '../www/js/engine.js';

const ramp = (n, from, to) => Array.from({ length: n }, (_, i) => from + (to - from) * i / (n - 1));
const wave = n => Array.from({ length: n }, (_, i) => 100 + 30 * Math.sin(i / 20));

// 1. pas assez de données -> abstention
assert.equal(E.analyze(ramp(30, 1, 2), 2).zone.key, 'none');

// 2. prix au sommet d'une forte hausse -> rouge
const up = ramp(400, 10, 100);
const a1 = E.analyze(up, 140);
assert.equal(a1.zone.key, 'red', 'sommet = moment de vendre');

// 3. prix au plus bas -> vert
const a2 = E.analyze(wave(400), 70);
assert.equal(a2.zone.key, 'green', 'creux = bon moment');

// 4. prix moyen -> jaune
const a3 = E.analyze(wave(400), 100);
assert.ok(['yellow', 'green'].includes(a3.zone.key));

// 5. règle GEOD : jamais de vente sous x20, vente "à décider" au-dessus
const rules = { geodBase: 0.21, geodMult: 20 };
const geodRow = { symbol: 'GEOD', qty: 500, price: 0.18, value: 90, target: 0.2, weight: 0.5 };
assert.equal(E.sellAdvice(geodRow, a1, 180, rules), null, 'pas de vente GEOD à 0,18');
const geodX20 = { ...geodRow, price: 4.3, value: 2150 };
const s = E.sellAdvice(geodX20, a1, 2500, rules);
assert.equal(s.action, 'sell'); assert.equal(s.decide, true); assert.equal(s.eur, null);

// 6. vente rouge : montant = excédent sur la cible
const btc = { symbol: 'BTC', qty: 0.01, price: 1000, value: 600, target: 0.45, weight: 0.6 };
const s2 = E.sellAdvice(btc, a1, 1000, rules);
assert.equal(Math.round(s2.eur), 150); assert.ok(s2.why.length > 10); assert.ok(s2.after);

// 7. plan du mois : rien sur les zones chères, total <= budget, cash respecté
const analyses = { BTC: a2, ETH: a1, GEOD: a3 };
const rows = [
  { symbol: 'BTC', name: 'Bitcoin', target: 0.45, value: 100, weight: 0.33, price: 50000 },
  { symbol: 'ETH', name: 'Ethereum', target: 0.2, value: 100, weight: 0.33, price: 3000 },
  { symbol: 'GEOD', name: 'Geodnet', target: 0.2, value: 100, weight: 0.33, price: 0.18 },
];
const plan = E.monthlyPlan({ rows, total: 300, analyses, amount: 800, cash: 600, date: new Date(2026, 9, 28) });
assert.equal(plan.budget, 600, 'plafonné au cash');
assert.equal(plan.items.find(i => i.symbol === 'ETH').eur, 0, 'pas d\'achat en zone rouge');
const sum = plan.items.reduce((t, i) => t + i.eur, 0);
assert.ok(sum <= 601 && sum >= 595, 'budget réparti : ' + sum);
assert.equal(plan.weeks, 1);

// 8. bulletin
const log = E.recordSignals([], [{ symbol: 'BTC', action: 'buy', eur: 50 }], { BTC: { eur: 100 } }, 0);
const g = E.grade(log, { BTC: { eur: 120 } }, 31 * 86400000);
assert.equal(g.good, 1); assert.equal(g.score, 1);
assert.equal(E.recordSignals(log, [{ symbol: 'BTC', action: 'buy' }], { BTC: { eur: 1 } }, 1000).length, 1, 'pas de doublon le même jour');

// 9. toi vs marché : acheter du BTC = marché
const D = 86400000;
const hist = { BTC: Array.from({ length: 10 }, (_, i) => ({ t: i * D, c: 100 + i * 10 })) };
const perf = E.performance([{ t: 0, symbol: 'BTC', side: 'BUY', qty: 1 }], hist);
assert.equal(Math.round(perf.mineNow), Math.round(perf.marketNow));
assert.equal(perf.investedNow, 100);

console.log('Moteur : tous les tests passent ✓');

// 10. toi face au marché (portefeuille réel) : finit au vrai total, BTC seul = marché
{
  const D = 86400000, t0 = Date.now() - 10 * D;
  const hist = { BTC: Array.from({ length: 10 }, (_, i) => ({ t: t0 + i * D, c: 100 + i * 10 })),
                 GEOD: Array.from({ length: 10 }, (_, i) => ({ t: t0 + i * D, c: 1 })) };
  const r1 = E.portfolioVsMarket({ BTC: 2 }, [], hist, t0, { BTC: 200 });
  assert.equal(Math.round(r1.mineNow), 400, 'finit au vrai total');
  assert.equal(Math.round(r1.marketNow), 400, 'que du BTC = marché');
  // achat de BTC au jour 5 : avant, on en avait 1 de moins
  const r2 = E.portfolioVsMarket({ BTC: 2 }, [{ t: t0 + 5 * D + 1000, symbol: 'BTC', side: 'BUY', qty: 1, eurPrice: 150 }], hist, t0, { BTC: 200 });
  assert.equal(Math.round(r2.mine[0].v), 100, 'départ = 1 seul BTC avant l\'achat');
  assert.equal(Math.round(r2.mineNow), Math.round(r2.marketNow), 'mêmes achats en BTC = marché');
  // GEOD stable pendant que le BTC monte : le marché fait mieux
  const r3 = E.portfolioVsMarket({ GEOD: 100 }, [], hist, t0, { BTC: 200, GEOD: 1 });
  assert.ok(r3.marketNow > r3.mineNow);
}
{
  const D = 86400000, t0 = Date.now() - 10 * D;
  const hist = { BTC: Array.from({ length: 10 }, (_, i) => ({ t: t0 + i * D, c: 100 })) };
  const fills = [
    { t: t0 - 30 * D, symbol: 'BTC', quote: 'EUR', side: 'BUY', qty: 5, eurPrice: 100, fee: 1 },
    { t: t0 + 3 * D, symbol: 'BTC', quote: 'EUR', side: 'SELL', qty: 1, eurPrice: 100, fee: 1 },
  ];
  const r = E.portfolioVsMarket({ BTC: 4 }, fills, hist, t0, { BTC: 100 }, 'BTC', 50);
  assert.equal(Math.round(r.investedNow), 50 + 501 - 99, 'versé = base + achats et frais - ventes nettes');
  assert.equal(Math.round(r.invested[0].v), 551, 'avant la vente');
}
{
  // conversion EUR -> USDC dans l'historique Coinbase : ne doit ni planter ni compter comme investi
  const D = 86400000, t0 = Date.now() - 5 * D;
  const hist = { BTC: Array.from({ length: 5 }, (_, i) => ({ t: t0 + i * D, c: 100 })) };
  const r = E.portfolioVsMarket({ BTC: 1 }, [{ t: t0, symbol: 'USDC', quote: 'EUR', side: 'BUY', qty: 50, eurPrice: null }], hist, t0, { BTC: 100 });
  assert.equal(r.investedNow, 0);
}
console.log('Toi face au marché : tests passent ✓');
