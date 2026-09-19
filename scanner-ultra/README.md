# LumaScan Ultra

Privacy-first Android document scanner built with Jetpack Compose and on-device ML Kit.

## Implemented in v1.0
- Premium dark Compose UI
- Single-page and multi-page batch scanning
- Automatic document detection/cropping and scanner enhancement controls
- PDF + JPEG scan pipeline
- Bundled on-device Latin OCR
- Search by OCR text inside saved scans
- Contextual naming for invoices, receipts, agreements, statements and IDs
- Local-only scan library
- PDF sharing with smart subject
- No analytics SDK, backend, or app-owned cloud service

## Planned modules
- Continuous hands-free auto-scan
- Custom CameraX motion/stability trigger
- Book spread splitting and dewarping
- Shadow/glare/finger inpainting
- Non-destructive manual crop history
- Page reorder/insert/delete editor
- Table/invoice extraction to CSV/XLSX
- Folders/tags and If/Then smart routing
- Explicit opt-in Google Drive sync
- TXT/DOCX export

## Build

AGP 9.4.0, Gradle 9.6, JDK 17.

```bash
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
