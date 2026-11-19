# Phone Medic

Appli Android de diagnostic smartphone : écran, tactile, capteurs, son, caméras, batterie (santé, température, cycles), performances, et un onglet Optimisation (doublons, applis inutilisées, cache, stockage).

L'interface est une page web (`app/src/main/assets/www/index.html`) affichée dans une WebView. `MainActivity.java` y ajoute un pont natif (`window.Android`) pour ce qu'un navigateur ne peut pas faire.

## Compiler

Chaque envoi sur `main` lance GitHub Actions, qui compile l'APK et la publie dans **Releases**.
En local : `gradle assembleDebug` (Android SDK, JDK 17, Gradle 8.7+).

La clé `app/debug.keystore` est une clé de debug standard (mot de passe `android`). Elle sert uniquement à ce que chaque nouvelle version s'installe par-dessus la précédente.
