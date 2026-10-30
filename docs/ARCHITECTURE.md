# Architecture d'SnipeX

Application Android native : **Kotlin + Jetpack Compose**, architecture en couches (Clean Architecture simplifiée, MVVM).

```
app/src/main/java/be/maikel/xplorer/
├── domain/        Logique pure, sans Android, entièrement testée
│   ├── Indicators.kt   SMA, EMA, RSI, MACD, Bollinger, ATR, ADX/DMI, Stochastic RSI, OBV,
│   │                   volatilité historique, pivots, supports/résistances, Fibonacci
│   ├── Ledger.kt       Positions, prix de revient moyen (frais inclus), réalisé/latent,
│   │                   capital apporté, transferts externes, trous d'historique ;
│   │                   historique de valeur, TWR, Dietz modifié, drawdown, benchmark BTC
│   ├── Decision.kt     Score 0-100 par familles d'indicateurs, confiance, risque, qualité des
│   │                   données, gardien du risque (veto), règle GEOD, plan de la semaine
│   ├── Journal.kt      Notation des conseils à échéance, radar d'opportunités
│   └── Money.kt        Formats français
├── data/          Accès aux données
│   ├── CoinbaseAuth.kt JWT ES256 (clé CDP ECDSA), aucune clé en dur
│   ├── CoinbaseApi.kt  Requêtes GET uniquement (lecture seule)
│   ├── SecretStore.kt  Clé Coinbase chiffrée AES-256-GCM par l'Android Keystore
│   ├── Db.kt           Room : opérations, soldes, bougies journalières, journal, alertes,
│   │                   intentions « J'ai acheté », corrections (audit)
│   ├── Prefs.kt        Réglages
│   └── Repo.kt         Synchronisation complète puis incrémentale, cache hors connexion
├── core/          Orchestration : Analysis.kt (tout le calcul), Pipeline.kt (journal, notifications)
├── work/          WorkManager (synchro ~1 h) et notifications
└── ui/            Écrans Compose, graphiques dessinés sur Canvas
```

## Flux

1. **Synchronisation** (`Repo.sync`) : prix (`/v2/exchange-rates`), produits, comptes v2, transactions v2 de chaque compte utile (paginées, arrêt dès qu'une transaction connue est revue), exécutions Advanced v3, bougies journalières (3 ans), bougies 4 h.
2. **Analyse** (`Analyzer.run`) : grand livre → positions et performance → historique → recommandations → plan → radar → santé.
3. **Après synchro** (`Pipeline.afterSync`) : journal des conseils (jamais réécrit), notation à 21 jours, rapprochement des achats déclarés, notifications (doublons supprimés, heures silencieuses, plafond quotidien).

## Choix importants

- **Source de vérité des quantités : les soldes Coinbase.** L'historique sert au prix de revient et à la courbe passée, reconstruite en remontant le temps depuis les soldes réels : la fin de la courbe est toujours juste, et un trou d'historique est signalé au lieu d'être comblé.
- **Capital apporté = virements en euros − retraits.** Les transferts de cryptos depuis l'extérieur sont suivis à part et ne sont jamais comptés comme des gains.
- **Un vote par famille d'indicateurs** (valorisation, momentum, tendance, risque) pour ne pas compter plusieurs fois la même information.
- **Le score n'est pas une probabilité.** Confiance, risque et qualité des données sont affichés séparément.
- **Aucun appel d'écriture** : contrôlé à chaque compilation par la CI.
