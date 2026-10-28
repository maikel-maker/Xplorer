# Installer Xplorer’ sur un Samsung

1. Sur le téléphone, ouvre : https://github.com/maikel-maker/Xplorer/releases/latest/download/Xplorer.apk
2. Ouvre le fichier téléchargé. Si Android demande l'autorisation d'installer des applications inconnues pour ton navigateur ou « Mes fichiers », accepte pour cette source.
3. **Installer**. Les versions suivantes s'installent par-dessus (mêmes réglages, même journal).
4. Au premier lancement, autorise les notifications si tu veux les alertes.
5. Réglages › connecte ta clé Coinbase (voir COINBASE.md).

Conseil Samsung : Paramètres › Applications › Xplorer’ › Batterie › **Non restreinte**, sinon One UI peut retarder fortement la synchronisation en arrière-plan.

## Permissions Android

| Permission | Pourquoi |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Lire Coinbase et les prix |
| `POST_NOTIFICATIONS` | Alertes (Android 13+, refusable) |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `FOREGROUND_SERVICE` | Ajoutées par WorkManager pour la synchronisation périodique |

Aucune autre permission : ni contacts, ni stockage, ni localisation, ni caméra.
