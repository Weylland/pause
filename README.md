# Fil des jours

Fil des jours, auparavant Pause, est un journal personnel pour Android et Windows. Il regroupe habitudes, pauses, pesées, repas et bilans.

## Installer

Télécharge la version qui convient depuis les [versions GitHub](https://github.com/Weylland/pause/releases/latest).

- **Android 8 ou plus récent**. Ouvre le fichier APK. Pour une mise à jour, installe-le par-dessus la version précédente.
- **Windows x64**. Décompresse l’archive Windows, puis ouvre `Pause.exe`. Le guide livré avec le ZIP explique la mise à jour et la configuration MCP.

## Utiliser Fil des jours

- **Calendrier**. Choisis la date et la durée de ta pause, puis note ta réponse quotidienne avec **Tenu** ou **Non tenu**. Le badge de série évolue après 7, 14 et 30 jours tenus d’affilée. Utilise **Prolonger** pour ajouter des jours à ton objectif en gardant tes réponses et ta date de départ.
- **Mes suivis**. Crée plusieurs pauses ou habitudes, par exemple **Anglais** ou **Vélo**, avec ou sans date de fin. Tu peux réserver des jours de repos. Avec 30 jours et 6 repos, l’objectif est de 24 jours cochés. Un repos dans le quota préserve la série. Les suivis se synchronisent entre Android et Windows et restent accessibles via le MCP Windows.
- **Poids et calories**. Saisis tes pesées, fixe tes objectifs et ajoute des aliments à la main, par code-barres, par photo du tableau nutritionnel ou en décrivant un repas. Les produits scannés sans calories passent dans **À estimer** pour la prochaine synchro. Les graphiques montrent l’évolution du poids, les calories et, sur Windows, les jours de pause.
- **Rappels**. Sur Android, choisis l’heure du rappel de journal. Active **Me rappeler aussi la pesée** pour recevoir un rappel si le poids du jour manque.
- **Synchro**. L’onglet **Synchro** sur Android et le bouton **Google Drive** sur Windows permettent de connecter le même compte Google. L’app synchronise les entrées à l’ouverture, après une saisie et périodiquement tant qu’elle reste ouverte. Si l’historique Drive est incomplet, **Remplacer le contenu de Drive** repart du journal d’un appareil. Le bouton **Associer Android** donne accès à la synchro Wi-Fi avec un QR code.
- **Estimations**. Sur Android, **À la synchro** garde le repas en un appui. Tu peux reprendre un brouillon avec **Estimer ici**. Sur Windows, Fil des jours prépare une proposition à partir de Ciqual ou d’Open Food Facts après la réception du brouillon. Ouvre **Repas à estimer**, choisis une référence Ciqual si plusieurs correspondent, complète les quantités manquantes et valide avec **Ajouter au journal**. Les quantités se lisent en grammes, en litres ou en nombre d’unités ; un nombre utilise un poids usuel à vérifier et 1 ml compte pour 1 g.
- **ChatGPT sur Android**. Dans **Calories**, utilise **Continuer avec ChatGPT** et autorise l’usage de ton abonnement éligible. Lorsque Ciqual ne reconnaît pas un aliment, seuls les noms inconnus sont envoyés pour l’estimation. Tu peux vérifier les valeurs avant de les enregistrer. L’estimation Ciqual fonctionne aussi hors connexion.
- **MCP**. Sur Windows, le bouton **Connecteur MCP** donne la configuration pour Claude Desktop, Claude Code et Codex. Le connecteur peut lire le journal, ajouter ou corriger des saisies et enregistrer un bilan à la demande.

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
