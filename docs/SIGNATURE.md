# Signature Android

L'APK release est signée en CI. Deux modes :

1. **Recommandé : secrets GitHub.** Dans le dépôt : Settings › Secrets and variables › Actions › New repository secret :
   - `XP_KEYSTORE_B64` : le keystore encodé en base64 (`base64 -w0 xplorer.jks`)
   - `XP_STORE_PASSWORD` : son mot de passe
   La CI les utilise en priorité et n'imprime jamais leur valeur.
2. **Transition (actuel)** : le keystore `keystore/xplorer.jks` du dépôt, avec un avertissement dans chaque build.
   C'est le même que celui des versions précédentes, ce qui permet d'installer par-dessus.
   **Limite connue** : ce keystore et son mot de passe sont visibles dans un dépôt public. Ils ne donnent aucun accès à Coinbase ni à tes données, mais quelqu'un pourrait signer une fausse mise à jour. Installe uniquement depuis le lien officiel des Releases, et passe au mode 1 dès que possible (il faudra alors désinstaller une fois l'ancienne version, car la signature change).
