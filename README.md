# SnipeX

App Android qui te dit chaque semaine quoi acheter avec tes 250 €. Six signaux (tendance, surchauffe, soldes, prix juste, élan, régularité) votent crypto par crypto, Bulls (vert) contre Bears (rouge), et leur verdict ajuste la répartition du budget (part × 0,5 à × 1,5). Cibles : BTC 50 · ETH 25 · GEOD 15 · LINK 5 · TAO 5. Le budget part toujours en entier : rien n'est gardé de côté.

Honnêteté : testée sur 8 ans (BTC, ETH, LINK, réglée sur la 1re moitié, vérifiée sur la 2e), l'arène fait jeu égal avec une répartition fixe. Elle rend la décision lisible et ludique, elle ne promet pas de battre le marché. L'app affiche son propre test sur le passé.

Lecture seule sur Coinbase, aucun ordre passé. Ancien nom : Xplorer’ (mêmes données, même clé).

# SnipeX

Application Android personnelle de gestion patrimoniale crypto et d'aide à la décision, connectée à Coinbase **en lecture seule**.

**Télécharger** : https://github.com/maikel-maker/Xplorer/releases/latest/download/Xplorer.apk

SnipeX répond à cinq questions : ce que je possède vraiment, combien j'ai gagné ou perdu et pourquoi, s'il est statistiquement intéressant d'acheter, conserver ou vendre, quel montant est raisonnable, et ce que disent les données, y compris contre la recommandation. Quand les preuves manquent, il le dit.

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
