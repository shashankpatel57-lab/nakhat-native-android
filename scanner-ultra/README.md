# SCANTANTRA

Privacy-first Android document scanner built with Jetpack Compose, ML Kit, Google Identity Services and the Google Drive REST API.

## v1.4

### Google Drive integration

v1.4 no longer uses Android's `ACTION_OPEN_DOCUMENT_TREE` / system Drive folder picker. Some Google Drive document-provider/device combinations refuse directory-tree grants and show "To protect your privacy, choose another folder" even inside My Drive.

SCANTANTRA now uses Google's supported OAuth authorization flow and Drive REST API instead:

1. User taps **Connect Google Drive** in Settings.
2. Google Identity Services asks the user to choose/authorize a Google account.
3. SCANTANTRA creates or reuses an app-created `SCANTANTRA Backup` folder in My Drive.
4. A real upload/delete test verifies Drive write access.
5. New scans are queued for automatic backup through WorkManager.
6. Failed uploads retry when network access returns, with a periodic catch-up job.

### Google Cloud configuration required for test APK

Google requires Android apps requesting Drive scopes to have an Android OAuth client registered for the exact package and signing certificate.

- Enable **Google Drive API** in a Google Cloud project.
- Configure the Google Auth consent screen.
- Create OAuth client type **Android**.
- Package name: `app.lumascan.ultra`
- SHA-1 for the stable v1.4+ test key:
  `42:60:C5:94:A1:C8:C5:B8:E0:9B:6C:3A:7F:E5:0C:95:8E:7D:BE:59`
- Request scope: `https://www.googleapis.com/auth/drive.file`
- If the OAuth app is in Testing mode, add the Gmail account used for testing as a test user.

The stable debug signing key is persisted in GitHub Actions cache using key `scantantra-oauth-debug-keystore-v1` so future test builds can keep the same OAuth SHA-1.

### Scanner features retained

- High-resolution single and batch scanning
- Blur detection and page-turn frame rejection
- PDF and JPG output
- Color, Grayscale and Black & White modes
- On-device OCR and contextual naming
- Search, folders and color tags
- Smart email subject
- Recent-scans Home plus All documents library
- SCANTANTRA launcher branding
- Smart shadow/glare cleanup

## Privacy

The app has no analytics SDK and no SCANTANTRA-owned backend. Scans and OCR remain local unless the user explicitly shares them or authorizes their Google Drive account. The Drive integration requests the narrow `drive.file` scope and can access files/folders created or authorized for this app rather than the user's entire Drive.

## Build

AGP 9.4.0, Gradle 9.6, JDK 17. Minimum Android version is API 24 (Android 7.0) because current Google Identity Services requires API 24+.

```bash
gradle :app:assembleDebug
```

Package: `app.lumascan.ultra`
Version: `1.4.0`
