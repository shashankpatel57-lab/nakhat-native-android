# SCANTANTRA

Privacy-first Android document scanner built with Jetpack Compose and on-device ML Kit.

## v1.3

### Google Drive automatic backup
- Android does not allow apps to select the Google Drive root itself.
- In Settings, choose **Set backup folder**.
- In the system picker open **My Drive**, create or open a subfolder such as **SCANTANTRA Backup**, then choose **Use this folder**.
- SCANTANTRA performs a real create/write/delete test before it marks the folder as connected.
- The persistable folder permission survives app restarts.
- Every completed scan is queued for automatic backup through WorkManager.
- Failed uploads retry with exponential backoff once network access is available.
- A periodic catch-up job runs every 6 hours for documents that were not yet backed up.
- **Backup all now** queues the complete local library immediately.
- The app stores the selected folder permission, never the user's Google password.

### Existing scanner features
- High-resolution single and batch scanning
- Blur detection and page-turn frame rejection
- PDF and JPG output
- Color, Grayscale and Black & White modes
- On-device OCR and contextual naming
- Search, folders and color tags
- Smart email subject
- Recent-scans Home plus All documents library
- SCANTANTRA launcher branding

## Privacy

The app has no analytics SDK and no app-owned backend. Scans, OCR text, folders and tags remain in app-private storage until the user explicitly shares them or enables their own Drive destination.

## Build

AGP 9.4.0, Gradle 9.6, JDK 17.

```bash
gradle :app:assembleDebug
```

Package: `app.lumascan.ultra`
Version: `1.3.0`
