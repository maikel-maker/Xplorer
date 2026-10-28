# Xplorer’

Application Android personnelle de gestion patrimoniale crypto et d'aide à la décision, connectée à Coinbase **en lecture seule**.

**Télécharger** : https://github.com/maikel-maker/Xplorer/releases/latest/download/Xplorer.apk

Xplorer’ répond à cinq questions : ce que je possède vraiment, combien j'ai gagné ou perdu et pourquoi, s'il est statistiquement intéressant d'acheter, conserver ou vendre, quel montant est raisonnable, et ce que disent les données, y compris contre la recommandation. Quand les preuves manquent, il le dit.

- Aucune fonction d'achat, de vente ni de retrait n'existe dans le code (vérifié à chaque compilation).
- La clé Coinbase est chiffrée par l'Android Keystore et ne quitte jamais le téléphone.
- Aucun serveur, aucune télémétrie, aucune donnée inventée.
- Aucun signal n'est une garantie de bénéfice. Ce n'est pas un conseil financier.

## Documentation
- [Installation sur Samsung](docs/INSTALLATION.md)
- [Connexion Coinbase](docs/COINBASE.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Tests](docs/TESTS.md)
- [Signature Android](docs/SIGNATURE.md)
- [Limites connues](docs/LIMITES.md)
- [Feuille de route](docs/ROADMAP.md)

## Compilation
GitHub Actions (`.github/workflows/apk.yml`) : contrôle « lecture seule », tests des endpoints publics Coinbase, tests unitaires, compilation, vérification de la signature et des permissions, publication de l'APK.
