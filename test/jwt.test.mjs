// Vérifie que le JWT signé par l'app est valide (clé de test générée localement)
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createVerify, createPublicKey } from 'node:crypto';
import { buildJwt, parseKeyInput } from '../www/js/coinbase.js';
const [pemPath, pubPath] = process.argv.slice(2);
const pem = readFileSync(pemPath, 'utf8');
const name = 'organizations/test/apiKeys/test';
const json = JSON.stringify({ name, privateKey: pem });
const creds = parseKeyInput('', json);
assert.equal(creds.name, name);
const jwt = await buildJwt(creds.name, creds.pem, 'GET', '/api/v3/brokerage/accounts');
const [h, p, s] = jwt.split('.');
const payload = JSON.parse(Buffer.from(p, 'base64url'));
assert.equal(payload.uri, 'GET api.coinbase.com/api/v3/brokerage/accounts');
const ok = createVerify('SHA256').update(`${h}.${p}`).verify({ key: createPublicKey(readFileSync(pubPath)), dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url'));
assert.ok(ok, 'signature valide');
console.log('JWT Coinbase : signature valide ✓');
