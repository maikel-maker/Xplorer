// Tests de l'analyse technique (séries synthétiques)
import assert from 'node:assert/strict';
import { technical, macd, fibonacci, emaSeries } from '../www/js/ta.js';
const ramp = (n, a, b) => Array.from({ length: n }, (_, i) => a + (b - a) * i / (n - 1));
// tendance haussière régulière : moyennes positives, croisement doré
const up = technical(ramp(300, 100, 200), 201);
assert.equal(up.items.find(i => i.key === 'ma200').status, 'pos');
assert.equal(up.items.find(i => i.key === 'cross').status, 'pos');
assert.equal(up.items.find(i => i.key === 'rsi').status, 'neg', 'hausse continue = surchauffe');
// tendance baissière
const down = technical(ramp(300, 200, 100), 99);
assert.equal(down.items.find(i => i.key === 'cross').status, 'neg');
assert.equal(down.items.find(i => i.key === 'rsi').status, 'pos', 'baisse continue = survente');
assert.ok(down.summary.length > 10);
// EMA d'une constante = constante ; MACD d'une constante = 0
assert.equal(Math.round(emaSeries(Array(50).fill(10), 12).at(-1)), 10);
assert.equal(Math.round(macd(Array(80).fill(10)).hist * 1e6), 0);
// Fibonacci : niveaux entre bas et haut
const f = fibonacci([...ramp(200, 50, 150)]);
assert.equal(f.up, true); assert.equal(Math.round(f.levels.find(l => l.r === 0.5).v), 100);
// pas assez de données
assert.equal(technical(ramp(20, 1, 2), 2), null);
console.log('Analyse technique : tous les tests passent ✓');
