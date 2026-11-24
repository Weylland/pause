# Pause

Pause est un journal personnel pour Android et Windows. Il permet de définir un objectif de pause, de suivre les pesées et les calories, et de conserver des bilans.

## Installer

Télécharge la version qui convient depuis les [versions GitHub](https://github.com/Weylland/pause/releases/latest).

- **Android 8 ou plus récent**. Ouvre le fichier APK. Pour une mise à jour, installe-le par-dessus la version précédente.
- **Windows x64**. Décompresse l’archive Windows, puis ouvre `Pause.exe`. Le guide livré avec le ZIP explique la mise à jour et la configuration MCP.

## Utiliser Pause

- **Calendrier**. Choisis la date et la durée de ta pause, puis note ta réponse quotidienne avec **Tenu** ou **Non tenu**. Le badge de série évolue après 7, 14 et 30 jours tenus d’affilée. Utilise **Prolonger** pour ajouter des jours à ton objectif en gardant tes réponses et ta date de départ.
- **Poids et calories**. Saisis tes pesées, fixe tes objectifs et ajoute des aliments à la main, par code-barres, par photo du tableau nutritionnel ou en décrivant un repas. Les produits scannés sans calories passent dans **À estimer** pour la prochaine synchro. Les graphiques montrent l’évolution du poids, les calories et, sur Windows, les jours de pause.
- **Rappels**. Sur Android, choisis l’heure du rappel de journal. Active **Me rappeler aussi la pesée** pour recevoir un rappel si le poids du jour manque.
- **Synchro**. Sur le même réseau local, ouvre **Associer Android** sur Windows, puis scanne le QR code dans l’onglet **PC** d’Android.
- **MCP**. Sur Windows, le bouton **Connecteur MCP** donne la configuration pour Claude Desktop, Claude Code et Codex. Le connecteur peut lire le journal, ajouter ou corriger des saisies et enregistrer un bilan à la demande. Les repas laissés **À estimer** remontent sur Windows à la synchro. Demande à ton IA une estimation, vérifie-la, puis demande l’enregistrement. Les bilans et repas validés apparaissent sur Android après la synchro.

Le journal est stocké sur les appareils. La synchronisation utilise TLS sur le réseau local. La recherche d’un produit envoie son code-barres à [Open Food Facts](https://world.openfoodfacts.org/). Les références de repas en texte libre proviennent de la [table Ciqual](nutrition/README.md), embarquée dans l’application.

## Compiler depuis les sources

### Android

Ouvre le dossier `android` dans Android Studio avec le JDK 17 ou plus récent et le SDK Android 36. Sous Windows, lance `.\gradlew.bat :app:assembleDebug` depuis ce dossier. Le wrapper peut être lancé avec `./gradlew :app:assembleDebug` sous Linux ou macOS.

### Windows

Utilise le SDK .NET 8 sous Windows. Depuis le dossier `windows`, lance cette commande pour générer un exécutable autonome.

```powershell
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true
```

La lecture des codes-barres utilise ZXing. La reconnaissance de texte utilise ML Kit. Le QR code sur Windows utilise QRCoder. Les fichiers de licence ZXing et QRCoder sont joints dans leurs dossiers respectifs.
