# Connecter Coinbase en lecture seule

1. Sur ton téléphone, ouvre **portal.cdp.coinbase.com** et connecte-toi avec ton compte Coinbase.
2. Onglet **API Keys** › **Secret API Keys** › **Create API key**.
3. Nom : `Xplorer`.
4. **API restrictions** : coche **uniquement View**. Ni Trade, ni Transfer.
5. **Advanced Settings** : algorithme **ECDSA** (obligatoire, Ed25519 n'est pas accepté par Coinbase App).
6. **Create API key**. Copie tout de suite :
   - le **nom** (`organizations/…/apiKeys/…`) ;
   - la **clé privée** (`-----BEGIN EC PRIVATE KEY----- … -----END EC PRIVATE KEY-----`).
7. Dans SnipeX : **Réglages › Coinbase en lecture seule**, colle les deux, puis **Connecter**.

La clé est vérifiée par un appel de lecture, puis chiffrée par le coffre-fort Android. Elle n'est jamais envoyée ailleurs qu'à Coinbase (pour signer chaque requête), jamais à un modèle d'IA, jamais dans un chat.

**Supprimer la clé** : Réglages › Supprimer la clé et les données Coinbase (effet immédiat). Tu peux aussi la révoquer sur le portail Coinbase.

## Endpoints utilisés (GET uniquement)

| Donnée | Endpoint |
|---|---|
| Comptes et soldes | `GET /v2/accounts` |
| Historique complet (achats, ventes, conversions, virements, transferts, récompenses) | `GET /v2/accounts/:id/transactions` |
| Exécutions réelles Advanced (prix, frais) | `GET /api/v3/brokerage/orders/historical/fills` |
| Prix en euros (public) | `GET /v2/exchange-rates?currency=EUR` |
| Produits et variation 24 h (public) | `GET /api/v3/brokerage/market/products` |
| Bougies 1 min → 1 jour (public, 350 max par requête) | `GET /api/v3/brokerage/market/products/:id/candles` |

Seules les transactions au statut `completed` sont prises en compte : un ordre placé ou annulé n'est jamais confondu avec une exécution.
