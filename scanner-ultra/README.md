# LumaScan Ultra

Privacy-first Android document scanner built with Jetpack Compose and on-device ML Kit.

## v1.1

The capture engine remains ML Kit Document Scanner, while the surrounding app has been redesigned around document workflows.

### Library
- High-contrast dark UI with explicit readable text colors
- Safe top and bottom insets
- PDF viewer action for every saved scan
- Full-text search over OCR content
- PDF or JPG sharing
- Smart email subject based on the document name
- Delete confirmation
- Manual Drive upload
- Existing v1 scans migrate into the v2 library format

### Scan options
- Single page or batch capture
- Per-scan PDF / JPG preference
- Color, Grayscale and Black & White output
- Configurable JPEG quality
- Smart shadow / glare cleanup using fully local luminance normalization
- The original ML Kit crop and review experience remains intact

### Organization
- Unlimited custom folders
- Color-coded custom tags
- Organize any saved document into a folder and multiple tags
- Filter the library by folder

### OCR and smart naming
- Bundled on-device Latin OCR
- Content search
- Contextual naming heuristics for invoices, receipts, leases, agreements, statements, passports and purchase orders
- Meaningful visible text is incorporated into names when possible

### Google Drive
- Connect a destination through Android's secure folder picker
- Works with Google Drive when the Drive document provider is available
- Persisted folder access without storing a Google password
- Optional automatic save after a scan is finalized
- Uploads the user's selected PDF or JPG output

### Settings
- Default export format
- Default color mode
- Smart cleanup toggle
- Cleanup strength
- JPEG quality
- OCR toggle
- Contextual naming toggle
- Drive connection and automatic save
- Smart email subject toggle
- Privacy explanation

## Privacy

The app has no analytics SDK and no app-owned backend. Scans, OCR text, folders and tags remain in app-private storage until the user explicitly shares them or enables their own Drive destination.

## Build

AGP 9.4.0, Gradle 9.6, JDK 17.

```bash
gradle :app:assembleDebug
```

Package: `app.lumascan.ultra`
Version: `1.1.0`
