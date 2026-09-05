# Remote Control App (Android)

Diffuse l'écran Android en direct (WebRTC) et reçoit les taps envoyés
depuis l'iPhone via le serveur de signalisation déployé sur Render.

## Utilisation

1. Compile via GitHub Actions (onglet "Actions" → récupère l'APK dans
   les "Artifacts" une fois le build terminé)
2. Installe l'APK sur ton Android
3. L'app affiche un code + un lien vers controller.html?code=XXXXXX
4. Envoie ce lien vers l'iPhone, ouvre-le dans Safari
5. Sur Android : active le service d'accessibilité puis démarre le
   partage d'écran

## Important

Usage prévu : contrôler à distance tes propres appareils uniquement.
