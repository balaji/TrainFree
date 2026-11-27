# Google Drive Backup OAuth Setup

Train Free backs up directly to `My Drive/TrainFree/trainfree-backup.json` using the non-sensitive `https://www.googleapis.com/auth/drive.file` scope.

Because `drive.file` scopes file access to the OAuth project that created the file, **Android and iOS must use OAuth 2.0 Client IDs from the same Google Cloud project** so backups can be restored across platforms.

## 1. Google Cloud Project Setup

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the **Google Drive API** (`drive.googleapis.com`).
3. Configure the **OAuth consent screen** and add the `https://www.googleapis.com/auth/drive.file` scope.

## 2. Android OAuth Client

Android uses Google Play services `AuthorizationClient` (`com.google.android.gms:play-services-auth`), which identifies the app by its package name and signing certificate SHA-1 — no client ID needs to be embedded in Android source files.

1. In **APIs & Services → Credentials → Create Credentials → OAuth client ID**, select **Android**.
2. Set package name to `dev.balaji.moonrose.trainfree`.
3. Add the SHA-1 fingerprint for your debug keystore (and another Android OAuth client for your release/Play App Signing SHA-1).

## 3. iOS OAuth Client

iOS uses native `ASWebAuthenticationSession` with OAuth 2.0 PKCE.

1. In **APIs & Services → Credentials → Create Credentials → OAuth client ID**, select **iOS**.
2. Set Bundle ID to match `PRODUCT_BUNDLE_IDENTIFIER` in `iosApp/Configuration/Config.xcconfig` (default `dev.balaji.moonrose.trainfree.trainfree$(TEAM_ID)`).
3. Copy `iosApp/Configuration/Secrets.xcconfig.example` to `iosApp/Configuration/Secrets.xcconfig` (gitignored) and fill in the generated Client ID and its reversed form:

   ```xcconfig
   GOOGLE_DRIVE_CLIENT_ID = 123456789012-abcdefg.apps.googleusercontent.com
   GOOGLE_DRIVE_REVERSED_CLIENT_ID = com.googleusercontent.apps.123456789012-abcdefg
   ```

4. Rebuild `iosApp` in Xcode. Builds without `Secrets.xcconfig` still compile and report `NOT_CONFIGURED` if Google Drive backup is tapped.
