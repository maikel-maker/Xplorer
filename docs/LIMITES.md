# Limites connues (Phase 1)

Ce qui n'est pas encore fait, ou fait de façon partielle. Rien de ce qui suit n'est présenté comme opérationnel dans l'app.

- **Assistant IA conversationnel, conseil des 10 agents** : Phase 2. Aucun modèle d'IA externe n'est appelé ; tout le calcul est local et déterministe.
- **Laboratoire de backtests** (walk-forward, Monte Carlo, Sharpe, Sortino…) : Phase 3. Le score n'a donc **pas encore été validé statistiquement** sur l'historique : le journal « Xplorer’ avait-il raison ? » sert à le mesurer en conditions réelles.
- **Données Coinbase réelles non testées par le développeur** : la logique est testée sur des historiques synthétiques, mais aucun test automatique n'a accès à ton compte. Les types d'opérations rares peuvent être mal classés : ils sont signalés dans « Qualité des données » et visibles dans Réglages › Ce qu'Xplorer’ a lu.
- **Comptes Coinbase lus** : les comptes à solde nul et jamais utilisés sont ignorés (jusqu'à 80 comptes lus). Une crypto entièrement revendue il y a longtemps peut manquer dans la courbe passée.
- **Frais des achats simples** (bouton « Acheter » de l'app Coinbase) : pris dans la contre-valeur euro donnée par Coinbase ; le détail des frais n'est pas toujours exposé par l'API.
- **Conversion USD/EUR** : les paires sans cotation en euros (ex. via USDC) sont converties avec le taux USDC-EUR journalier, ou le taux du jour à défaut.
- **Arrière-plan** : WorkManager, environ une fois par heure, quand Android le permet. Pas de WebSocket ni de surveillance permanente.
- **Graphiques** : chandeliers, volumes, MM20/50/200, Bollinger, Fibonacci, supports/résistances, prix de revient, marqueurs d'achats et ventes. Ichimoku, SuperTrend, VWAP, profil de volume et zones de liquidité ne sont pas encore tracés. Pas d'unité 1 min sur l'historique long (350 bougies max par requête intrajournalière).
- **Verrouillage biométrique** : pas encore disponible.
- **Signature** : voir SIGNATURE.md.
