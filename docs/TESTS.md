# Tests

Exécutés à chaque compilation par GitHub Actions. Une publication n'a lieu que si tout passe. Le bilan chiffré apparaît dans chaque build (« Tests unitaires : N exécutés, 0 échec »).

| Fichier | Ce qui est vérifié |
|---|---|
| `IndicatorsTest` | SMA, EMA, RSI, Bollinger, ATR, MACD comparés à des valeurs calculées **indépendamment avec pandas** sur une série déterministe (tolérance 1e-6) ; RSI et ATR recoupés avec le lissage `ewm` de pandas ; bornes du RSI, ADX sur tendance pure, Fibonacci, supports, agrégation hebdomadaire |
| `LedgerTest` | Capital = virements en euros uniquement ; prix de revient moyen frais inclus ; plus-value réalisée sur vente partielle ; récompenses à coût zéro ; conversions EUR→USDC neutres ; part sans historique signalée (pas inventée) ; transferts externes exclus des gains ; prix manquant = valeur non comptée ; courbe historique qui finit sur les soldes réels ; benchmark BTC = mêmes virements placés en BTC |
| `DecisionTest` | Pas de signal sans 120 jours d'historique ; jamais d'achat dans les 10 % les plus chers de l'année ; veto d'exposition ; règle GEOD (aucune vente sous x20) ; score borné et distinct de la confiance ; plan qui ne dépasse jamais le budget ni les limites ; alerte de liquidités ; notation du journal |
| `CoinbaseAuthTest` | JWT ES256 vérifié cryptographiquement avec une clé jetable (formats SEC1 et PKCS#8, fichier JSON Coinbase) ; claim `uri` exact ; refus d'un nom de clé invalide |

## Contrôles de la CI
- **Lecture seule** : la compilation échoue si le code contient une requête d'écriture (POST, PUT, DELETE) ou un endpoint d'ordre.
- **Endpoints publics Coinbase** : bougies, taux de change et produits sont appelés réellement et leur format est vérifié.
- **APK** : signature vérifiée (`apksigner verify`), permissions contrôlées.

## Non testé automatiquement
- Les endpoints authentifiés (comptes, transactions, exécutions) : impossible sans ta clé, qui ne doit jamais être mise dans GitHub. Ils sont validés à ta première connexion, et l'écran « Ce qu'SnipeX a lu chez Coinbase » permet de vérifier.
- L'interface sur ton téléphone (aucun appareil Android disponible pour le développeur).
