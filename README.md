# Fil des jours

Fil des jours, auparavant Pause, est un journal personnel pour Android et Windows. Il regroupe habitudes, pauses, pesées, repas et bilans.

## Installer

Télécharge la version qui convient depuis les [versions GitHub](https://github.com/Weylland/pause/releases/latest).

- **Android 8 ou plus récent**. Ouvre le fichier APK. Pour une mise à jour, installe-le par-dessus la version précédente.
- **Windows x64**. Décompresse l’archive Windows, puis ouvre `Pause.exe`. Le guide livré avec le ZIP explique la mise à jour et la configuration MCP.

## Utiliser Fil des jours

Les deux applications ont les mêmes quatre pages.

- **Aujourd’hui**. Réponds à ta pause avec **Tenu** ou **Non tenu**, écris ce que tu as mangé, coche tes habitudes et note ton poids. Choisis un jour dans la semaine pour relire ou compléter une autre date. Sans quantité ni calories, une portion habituelle est estimée et reste modifiable en touchant l’aliment. Si tu connais les calories, utilise **Ajouter avec ses calories** sur Windows ou le bouton **+** sur Android. Ce bouton permet aussi de vérifier avant d’ajouter, de garder le repas pour la synchro et de photographier un tableau nutritionnel. Android lit les codes-barres. Les produits scannés sans calories sont marqués **à estimer**.
- **Suivis**. Choisis la date et la durée de ta pause. Le badge de série évolue après 7, 14 et 30 jours tenus d’affilée. Utilise **Prolonger** pour ajouter des jours à ton objectif en gardant tes réponses et ta date de départ. Crée aussi plusieurs pauses ou habitudes, par exemple **Anglais** ou **Vélo**, avec ou sans date de fin. Tu peux réserver des jours de repos. Avec 30 jours et 6 repos, l’objectif est de 24 jours cochés. Un repos dans le quota préserve la série.
- **Évolution**. Consulte le poids, les calories et les jours de pause sur 7, 30 ou 90 jours, l’historique et les bilans.
- **Réglages**. Connecte **Google Drive** avec le même compte sur tes appareils ou associe-les par **Wi-Fi local** avec un QR code. L’app synchronise les entrées à l’ouverture, après une saisie et périodiquement tant qu’elle reste ouverte. Si l’historique Drive est incomplet, **Remplacer le contenu de Drive** repart du journal d’un appareil. Cette page donne aussi accès aux objectifs et, sur Android, au rappel quotidien, au rappel de pesée et à la source des estimations.
- **Repas à estimer**. Sur Android, touche un repas **à estimer** puis **Estimer ici**. Sur Windows, Fil des jours prépare une proposition à partir de Ciqual ou d’Open Food Facts. Choisis une référence si plusieurs correspondent, complète les quantités manquantes et valide avec **Ajouter au journal**. Les quantités se lisent en grammes, en litres ou en nombre d’unités ; un nombre utilise un poids usuel à vérifier et 1 ml compte pour 1 g.
- **ChatGPT sur Android**. Dans **Réglages**, ouvre **Estimation des repas** et autorise l’usage de ton abonnement éligible. Lorsque Ciqual ne reconnaît pas un aliment, seuls les noms inconnus sont envoyés pour l’estimation. Tu peux vérifier les valeurs avant de les enregistrer. L’estimation Ciqual fonctionne aussi hors connexion.
- **MCP**. Sur Windows, **Connecteur pour Claude et Codex** dans **Réglages** donne la configuration pour Claude Desktop, Claude Code et Codex. Le connecteur peut lire le journal, ajouter ou corriger des saisies et enregistrer un bilan à la demande.

Le journal est stocké sur les appareils. Avec l’accord du titulaire du compte, Drive conserve aussi l’état du journal et les derniers changements dans le [dossier privé de l’application](https://developers.google.com/workspace/drive/api/guides/appdata). La synchronisation Wi-Fi utilise TLS sur le réseau local. La recherche d’un produit envoie son code-barres à [Open Food Facts](https://world.openfoodfacts.org/). Les références de repas en texte libre proviennent de la [table Ciqual](nutrition/README.md), embarquée dans l’application.

## Configurer Google Drive

Active l’API Google Drive dans un projet Google Cloud, déclare `https://www.googleapis.com/auth/drive.appdata` et crée deux [clients OAuth](https://developers.google.com/workspace/guides/create-credentials) dans le même projet. Le client Android utilise le package `fr.pause.journal` et le SHA-1 du certificat qui signe l’APK. Le client de bureau fournit un fichier JSON à sélectionner lors de la première connexion sur Windows. Garde ce fichier privé. Si le projet est en mode Test, l’accès expire après sept jours. Le niveau Standard de Drive suffit et peut être utilisé sans compte de facturation.

## Compiler depuis les sources

### Android

Ouvre le dossier `android` dans Android Studio avec le JDK 17 ou plus récent et le SDK Android 36. Sous Windows, lance `.\gradlew.bat :app:assembleDebug` depuis ce dossier. Le wrapper peut être lancé avec `./gradlew :app:assembleDebug` sous Linux ou macOS.

### Windows

Utilise le SDK .NET 8 sous Windows. Depuis le dossier `windows`, lance cette commande pour générer un exécutable autonome.

```powershell
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true
```

### Tester les règles communes

`tests/repas.tsv` (estimation des repas) et `tests/drive.json` (synchro Drive) contiennent les cas et les résultats attendus, communs aux deux applications. Lance `.\gradlew.bat :app:testDebugUnitTest` depuis `android` et `dotnet test` depuis `windows-tests`.

La lecture des codes-barres utilise ZXing. La reconnaissance de texte utilise ML Kit. Le QR code sur Windows utilise QRCoder. Les fichiers de licence ZXing et QRCoder sont joints dans leurs dossiers respectifs.
