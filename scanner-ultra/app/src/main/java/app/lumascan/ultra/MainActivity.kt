package app.lumascan.ultra

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ScanDoc(
    val id: String,
    val title: String,
    val path: String,
    val pageCount: Int,
    val createdAt: Long,
    val ocrText: String
)

class MainActivity : ComponentActivity() {
    private var docs by mutableStateOf<List<ScanDoc>>(emptyList())
    private var scanPageLimit = 1

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return@registerForActivityResult
        saveScan(scan)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        docs = loadDocs()
        setContent {
            LumaTheme {
                LumaApp(
                    docs = docs,
                    onQuickScan = { launchScanner(1) },
                    onBatchScan = { launchScanner(50) },
                    onShare = { share(it) },
                    onDelete = { delete(it) }
                )
            }
        }
    }

    private fun launchScanner(limit: Int) {
        scanPageLimit = limit
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(limit)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
    }

    private fun saveScan(result: GmsDocumentScanningResult) {
        val pdf = result.pdf ?: return
        val dir = File(filesDir, "scans").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val dest = File(dir, "Scan_$stamp.pdf")
        contentResolver.openInputStream(pdf.uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }

        val doc = ScanDoc(
            id = UUID.randomUUID().toString(),
            title = if (scanPageLimit == 1) "Quick Scan $stamp" else "Batch Scan $stamp",
            path = dest.absolutePath,
            pageCount = result.pages?.size ?: 1,
            createdAt = System.currentTimeMillis(),
            ocrText = ""
        )
        docs = listOf(doc) + docs
        persistDocs()

        val pages = result.pages.orEmpty()
        if (pages.isEmpty()) return
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val chunks = MutableList(pages.size) { "" }
        var remaining = pages.size

        pages.forEachIndexed { index, page ->
            val image = InputImage.fromFilePath(this, page.imageUri)
            recognizer.process(image)
                .addOnSuccessListener { chunks[index] = it.text }
                .addOnCompleteListener {
                    remaining--
                    if (remaining == 0) {
                        recognizer.close()
                        val extracted = chunks.joinToString("\n")
                        val smarter = smartName(extracted, doc.title)
                        docs = docs.map {
                            if (it.id == doc.id) it.copy(title = smarter, ocrText = extracted) else it
                        }
                        persistDocs()
                    }
                }
        }
    }

    private fun smartName(text: String, fallback: String): String {
        val t = text.lowercase(Locale.US)
        val prefix = when {
            "invoice" in t -> "Invoice"
            "receipt" in t -> "Receipt"
            "lease" in t || "agreement" in t -> "Agreement"
            "statement" in t -> "Statement"
            "passport" in t -> "Identity Document"
            else -> return fallback
        }
        val date = SimpleDateFormat("MMM yyyy", Locale.US).format(Date())
        return "$prefix · $date"
    }

    private fun share(doc: ScanDoc) {
        val file = File(doc.path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, doc.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share scan"))
    }

    private fun delete(doc: ScanDoc) {
        File(doc.path).delete()
        docs = docs.filterNot { it.id == doc.id }
        persistDocs()
    }

    private fun persistDocs() {
        val a = JSONArray()
        docs.forEach {
            a.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("path", it.path)
                put("pages", it.pageCount)
                put("created", it.createdAt)
                put("ocr", Base64.encodeToString(it.ocrText.toByteArray(), Base64.NO_WRAP))
            })
        }
        getSharedPreferences("library", Context.MODE_PRIVATE)
            .edit().putString("docs", a.toString()).apply()
    }

    private fun loadDocs(): List<ScanDoc> = try {
        val raw = getSharedPreferences("library", Context.MODE_PRIVATE).getString("docs", "[]") ?: "[]"
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ScanDoc(
                o.getString("id"),
                o.getString("title"),
                o.getString("path"),
                o.getInt("pages"),
                o.getLong("created"),
                String(Base64.decode(o.optString("ocr", ""), Base64.DEFAULT))
            )
        }.filter { File(it.path).exists() }
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
fun LumaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF090A0F),
            surface = Color(0xFF11131B),
            primary = Color(0xFF9CF7D3),
            onPrimary = Color(0xFF052219),
            onBackground = Color(0xFFF5F7FA),
            onSurface = Color(0xFFF5F7FA)
        ),
        content = content
    )
}

@Composable
fun LumaApp(
    docs: List<ScanDoc>,
    onQuickScan: () -> Unit,
    onBatchScan: () -> Unit,
    onShare: (ScanDoc) -> Unit,
    onDelete: (ScanDoc) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var featuresOpen by remember { mutableStateOf(false) }
    val filtered = remember(docs, query) {
        if (query.isBlank()) docs else docs.filter {
            it.title.contains(query, true) || it.ocrText.contains(query, true)
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF121628), Color(0xFF090A0F), Color(0xFF090A0F)))
        )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFFB6FFD9), Color(0xFF7B9BFF)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("L", color = Color(0xFF071118), fontSize = 24.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("LumaScan Ultra", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        Text("Private. Local. Effortless.", color = Color(0xFF9BA3B6), fontSize = 12.sp)
                    }
                    Surface(shape = RoundedCornerShape(100.dp), color = Color(0x2237F5A5)) {
                        Text(
                            "ZERO-DATA",
                            color = Color(0xFF87F8C8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF151926),
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("Turn paper into clarity.", fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black)
                        Text(
                            "Auto-crop, clean, OCR and organize — processing stays on your device.",
                            color = Color(0xFFAFB5C5),
                            fontSize = 14.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onQuickScan,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF9CF7D3),
                                    contentColor = Color(0xFF052219)
                                ),
                                modifier = Modifier.weight(1f).height(56.dp)
                            ) { Text("＋  Quick scan", fontWeight = FontWeight.Bold) }

                            FilledTonalButton(
                                onClick = onBatchScan,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF252B3D)),
                                modifier = Modifier.weight(1f).height(56.dp)
                            ) { Text("▤  Batch", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ModeChip("AUTO CROP", "◆", Modifier.weight(1f))
                    ModeChip("OCR", "Aa", Modifier.weight(1f))
                    ModeChip("PDF", "↗", Modifier.weight(1f))
                }
            }

            item {
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF151821), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⌕", fontSize = 22.sp, color = Color(0xFF9CF7D3))
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (query.isBlank()) {
                                    Text("Search inside your scanned text", color = Color(0xFF737B8F), fontSize = 14.sp)
                                }
                                inner()
                            }
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (query.isBlank()) "Your library" else "Search results",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${filtered.size} scans", color = Color(0xFF7F8799), fontSize = 12.sp)
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF11141C), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("▱", fontSize = 42.sp, color = Color(0xFF9CF7D3))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (query.isBlank()) "Your clean slate starts here" else "No matching scan",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (query.isBlank()) "Scan a document and OCR will make its text searchable."
                                else "Try another word from inside the document.",
                                color = Color(0xFF858DA0),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { doc -> ScanCard(doc, onShare, onDelete) }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF10131A),
                    modifier = Modifier.fillMaxWidth().clickable { featuresOpen = !featuresOpen }
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ultra pipeline", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (featuresOpen) "−" else "+", color = Color(0xFF9CF7D3), fontSize = 20.sp)
                        }
                        AnimatedVisibility(featuresOpen) {
                            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Feature("Ready", "Single & batch capture, smart crop, filters, PDF/JPG result")
                                Feature("Ready", "Bundled on-device OCR + search inside documents")
                                Feature("Ready", "Contextual auto naming + private local storage")
                                Feature("Next", "Continuous auto-scan, book dewarp, glare/finger removal")
                                Feature("Next", "Tables → CSV/XLSX, tags/folders, smart routing, Drive sync")
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = Color(0xEE11131A),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Row(
                Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomItem("▣", "Library", true)
                Box(
                    Modifier.size(62.dp).clip(CircleShape).background(Color(0xFF9CF7D3)).clickable { onQuickScan() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋", color = Color(0xFF042019), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                BottomItem("⌁", "Privacy", false)
            }
        }
    }
}

@Composable
fun ModeChip(label: String, glyph: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF141822), modifier = modifier) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, color = Color(0xFF9CF7D3), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color(0xFF9EA6B9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ScanCard(doc: ScanDoc, onShare: (ScanDoc) -> Unit, onDelete: (ScanDoc) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF131720), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(58.dp).height(72.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFEFF4F1), Color(0xFFC9D4CF)))),
                contentAlignment = Alignment.Center
            ) {
                Text("PDF", color = Color(0xFF19201D), fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2)
                Spacer(Modifier.height(5.dp))
                Text(
                    "${doc.pageCount} page${if (doc.pageCount == 1) "" else "s"} · OCR indexed",
                    color = Color(0xFF858EA3),
                    fontSize = 12.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onShare(doc) }) { Text("Share", color = Color(0xFF9CF7D3)) }
                TextButton(onClick = { onDelete(doc) }) { Text("Delete", color = Color(0xFFBC8491), fontSize = 11.sp) }
            }
        }
    }
}

@Composable
fun Feature(status: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            status,
            color = if (status == "Ready") Color(0xFF9CF7D3) else Color(0xFFFFC56E),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(46.dp)
        )
        Text(text, color = Color(0xFF9AA2B5), fontSize = 12.sp)
    }
}

@Composable
fun BottomItem(glyph: String, label: String, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Text(glyph, color = if (selected) Color(0xFF9CF7D3) else Color(0xFF6E7587), fontSize = 20.sp)
        Text(label, color = if (selected) Color(0xFFF3F5F8) else Color(0xFF6E7587), fontSize = 10.sp)
    }
}
