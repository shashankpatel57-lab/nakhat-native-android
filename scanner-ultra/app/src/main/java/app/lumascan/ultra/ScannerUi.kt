package app.lumascan.ultra

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

object UiPalette {
    val bg = Color(0xFF090B10)
    val surface = Color(0xFF121620)
    val surface2 = Color(0xFF191E2A)
    val ink = Color(0xFFF7F9FC)
    val muted = Color(0xFFA6AEC0)
    val accent = Color(0xFF8EF0CF)
    val danger = Color(0xFFFF9AAC)
    val folderColors = listOf(
        0xFF8EF0CFL, 0xFF8DBBFFL, 0xFFFFC875L, 0xFFFF95B7L,
        0xFFC7A4FFL, 0xFF7FE5F0L, 0xFFA5E87EL, 0xFFFFA57EL
    )
}

@Composable
fun LumaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = UiPalette.bg,
            surface = UiPalette.surface,
            surfaceVariant = UiPalette.surface2,
            primary = UiPalette.accent,
            onPrimary = Color(0xFF052019),
            onBackground = UiPalette.ink,
            onSurface = UiPalette.ink,
            onSurfaceVariant = UiPalette.muted,
            error = UiPalette.danger
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerApp(
    docs: List<ScanDoc>,
    folders: List<ScanFolder>,
    tags: List<ScanTag>,
    settings: AppSettings,
    processing: Boolean,
    onScan: (Boolean, OutputFormat, ColorMode) -> Unit,
    onView: (ScanDoc) -> Unit,
    onShare: (ScanDoc, OutputFormat) -> Unit,
    onEmail: (ScanDoc) -> Unit,
    onDelete: (ScanDoc) -> Unit,
    onOrganize: (ScanDoc, String, List<String>) -> Unit,
    onUpload: (ScanDoc) -> Unit,
    onAddFolder: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onConnectDrive: () -> Unit,
    onOpenDrive: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var showScan by remember { mutableStateOf(false) }
    var scanBatch by remember { mutableStateOf(false) }
    var organizeDoc by remember { mutableStateOf<ScanDoc?>(null) }
    var showAllDocuments by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = UiPalette.bg,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF10131A), tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0; showAllDocuments = false },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("Library") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { scanBatch = false; showScan = true },
                    icon = { Icon(Icons.Rounded.CameraAlt, contentDescription = null) },
                    label = { Text("Scan") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    label = { Text("Folders") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> if (showAllDocuments) {
                    LibraryScreen(
                        docs = docs,
                        folders = folders,
                        tags = tags,
                        driveConnected = settings.driveTreeUri.isNotBlank(),
                        onNewScan = { batch -> scanBatch = batch; showScan = true },
                        onView = onView,
                        onShare = onShare,
                        onEmail = onEmail,
                        onDelete = onDelete,
                        onOrganize = { organizeDoc = it },
                        onUpload = onUpload
                    )
                } else {
                    HomeScreen(
                        docs = docs,
                        folders = folders,
                        tags = tags,
                        driveConnected = settings.driveTreeUri.isNotBlank(),
                        onNewScan = { batch -> scanBatch = batch; showScan = true },
                        onViewAll = { showAllDocuments = true },
                        onView = onView,
                        onShare = onShare,
                        onEmail = onEmail,
                        onDelete = onDelete,
                        onOrganize = { organizeDoc = it },
                        onUpload = onUpload
                    )
                }
                1 -> FoldersScreen(
                    docs = docs,
                    folders = folders,
                    tags = tags,
                    onAddFolder = onAddFolder,
                    onAddTag = onAddTag
                )
                else -> SettingsScreen(
                    settings = settings,
                    onSettings = onSettings,
                    onConnectDrive = onConnectDrive,
                    onOpenDrive = onOpenDrive
                )
            }

            if (processing) {
                Surface(
                    color = Color(0xFF202735),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Finishing your scan…", color = UiPalette.ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showScan) {
        ScanOptionsDialog(
            initialBatch = scanBatch,
            settings = settings,
            onDismiss = { showScan = false },
            onStart = { batch, format, mode ->
                showScan = false
                onScan(batch, format, mode)
            }
        )
    }

    organizeDoc?.let { doc ->
        OrganizeDialog(
            doc = doc,
            folders = folders,
            tags = tags,
            onDismiss = { organizeDoc = null },
            onSave = { folderId, selectedTags ->
                onOrganize(doc, folderId, selectedTags)
                organizeDoc = null
            }
        )
    }
}

@Composable
fun HomeScreen(
    docs: List<ScanDoc>,
    folders: List<ScanFolder>,
    tags: List<ScanTag>,
    driveConnected: Boolean,
    onNewScan: (Boolean) -> Unit,
    onViewAll: () -> Unit,
    onView: (ScanDoc) -> Unit,
    onShare: (ScanDoc, OutputFormat) -> Unit,
    onEmail: (ScanDoc) -> Unit,
    onDelete: (ScanDoc) -> Unit,
    onOrganize: (ScanDoc) -> Unit,
    onUpload: (ScanDoc) -> Unit
) {
    val recent = remember(docs) { docs.sortedByDescending { it.createdAt }.take(3) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 18.dp)
    ) {
        item {
            Text("SCANTANTRA", color = UiPalette.ink, fontSize = 29.sp, fontWeight = FontWeight.Black)
            Text("Document scanner", color = UiPalette.muted, fontSize = 13.sp)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onNewScan(false) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UiPalette.accent,
                        contentColor = Color(0xFF06231B)
                    )
                ) { Text("Scan page", fontWeight = FontWeight.Bold) }

                FilledTonalButton(
                    onClick = { onNewScan(true) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = UiPalette.surface2,
                        contentColor = UiPalette.ink
                    )
                ) { Text("Batch scan", fontWeight = FontWeight.Bold) }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent scans", color = UiPalette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onViewAll) { Text("View all (" + docs.size + ")") }
            }
        }

        if (recent.isEmpty()) {
            item { EmptyLibrary(hasDocs = false, onScan = { onNewScan(false) }) }
        } else {
            items(recent, key = { it.id }) { doc ->
                DocumentCard(
                    doc = doc,
                    folder = folders.firstOrNull { it.id == doc.folderId },
                    tagCatalog = tags,
                    driveConnected = driveConnected,
                    onView = { onView(doc) },
                    onShare = { format -> onShare(doc, format) },
                    onEmail = { onEmail(doc) },
                    onDelete = { onDelete(doc) },
                    onOrganize = { onOrganize(doc) },
                    onUpload = { onUpload(doc) }
                )
            }
        }

        item {
            FilledTonalButton(
                onClick = onViewAll,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = UiPalette.surface2,
                    contentColor = UiPalette.ink
                )
            ) {
                Text("All documents", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun LibraryScreen(
    docs: List<ScanDoc>,
    folders: List<ScanFolder>,
    tags: List<ScanTag>,
    driveConnected: Boolean,
    onNewScan: (Boolean) -> Unit,
    onView: (ScanDoc) -> Unit,
    onShare: (ScanDoc, OutputFormat) -> Unit,
    onEmail: (ScanDoc) -> Unit,
    onDelete: (ScanDoc) -> Unit,
    onOrganize: (ScanDoc) -> Unit,
    onUpload: (ScanDoc) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var folderFilter by remember { mutableStateOf("") }

    val filtered = remember(docs, query, folderFilter) {
        docs.filter { doc ->
            val folderOk = folderFilter.isBlank() || doc.folderId == folderFilter
            val searchOk = query.isBlank() ||
                doc.title.contains(query, true) ||
                doc.ocrText.contains(query, true) ||
                doc.tags.any { it.contains(query, true) }
            folderOk && searchOk
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text("Documents", color = UiPalette.ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text(
            docs.size.toString() + " scan" + if (docs.size == 1) "" else "s" + " · stored on this device",
            color = UiPalette.muted,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onNewScan(false) },
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UiPalette.accent,
                    contentColor = Color(0xFF06231B)
                )
            ) { Text("Scan page", fontWeight = FontWeight.Bold) }

            FilledTonalButton(
                onClick = { onNewScan(true) },
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = UiPalette.surface2,
                    contentColor = UiPalette.ink
                )
            ) { Text("Batch scan", fontWeight = FontWeight.Bold) }
        }

        Spacer(Modifier.height(14.dp))
        SearchBox(query = query, onQuery = { query = it })

        if (folders.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = folderFilter.isBlank(),
                    onClick = { folderFilter = "" },
                    label = { Text("All") }
                )
                folders.forEach { folder ->
                    FilterChip(
                        selected = folderFilter == folder.id,
                        onClick = { folderFilter = if (folderFilter == folder.id) "" else folder.id },
                        label = { Text(folder.name) }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        if (filtered.isEmpty()) {
            EmptyLibrary(hasDocs = docs.isNotEmpty(), onScan = { onNewScan(false) })
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filtered, key = { it.id }) { doc ->
                    DocumentCard(
                        doc = doc,
                        folder = folders.firstOrNull { it.id == doc.folderId },
                        tagCatalog = tags,
                        driveConnected = driveConnected,
                        onView = { onView(doc) },
                        onShare = { format -> onShare(doc, format) },
                        onEmail = { onEmail(doc) },
                        onDelete = { onDelete(doc) },
                        onOrganize = { onOrganize(doc) },
                        onUpload = { onUpload(doc) }
                    )
                }
            }
        }
    }
}

@Composable
fun SearchBox(query: String, onQuery: (String) -> Unit) {
    Surface(
        color = UiPalette.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⌕", color = UiPalette.accent, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = UiPalette.ink, fontSize = 15.sp),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isBlank()) {
                        Text("Search documents or text inside them", color = UiPalette.muted, fontSize = 14.sp)
                    }
                    inner()
                }
            )
        }
    }
}

@Composable
fun EmptyLibrary(hasDocs: Boolean, onScan: () -> Unit) {
    Surface(
        color = UiPalette.surface,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (hasDocs) "No matching documents" else "No scans yet",
                color = UiPalette.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hasDocs) "Try another search or folder." else "Scan your first document to build your library.",
                color = UiPalette.muted,
                fontSize = 13.sp
            )
            if (!hasDocs) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = onScan) { Text("Start scanning") }
            }
        }
    }
}

@Composable
fun DocumentCard(
    doc: ScanDoc,
    folder: ScanFolder?,
    tagCatalog: List<ScanTag>,
    driveConnected: Boolean,
    onView: () -> Unit,
    onShare: (OutputFormat) -> Unit,
    onEmail: () -> Unit,
    onDelete: () -> Unit,
    onOrganize: () -> Unit,
    onUpload: () -> Unit
) {
    var shareDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    Surface(
        color = UiPalette.surface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    color = Color(0xFFE8EEF0),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(width = 54.dp, height = 68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("PDF", color = Color(0xFF141A1A), fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        doc.title,
                        color = UiPalette.ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(5.dp))
                    val formatLabel = if (doc.preferredFormat == OutputFormat.PDF) "PDF" else "JPG"
                    val modeLabel = when (doc.colorMode) {
                        ColorMode.COLOR -> "Color"
                        ColorMode.GRAYSCALE -> "Grayscale"
                        ColorMode.BLACK_WHITE -> "B&W"
                    }
                    Text(
                        doc.pageCount.toString() + " page" + if (doc.pageCount == 1) "" else "s" +
                            " · " + formatLabel + " · " + modeLabel,
                        color = UiPalette.muted,
                        fontSize = 12.sp
                    )
                    folder?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("■ " + it.name, color = Color(it.color), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (doc.tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    doc.tags.forEach { name ->
                        val color = tagCatalog.firstOrNull { it.name == name }?.color ?: 0xFF8EF0CFL
                        Surface(
                            color = Color(color).copy(alpha = 0.16f),
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Text(
                                name,
                                color = Color(color),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onView) { Text("View", color = UiPalette.accent) }
                TextButton(onClick = { shareDialog = true }) { Text("Share", color = UiPalette.accent) }
                TextButton(onClick = onEmail) { Text("Email", color = UiPalette.ink) }
                TextButton(onClick = onOrganize) { Text("Organize", color = UiPalette.ink) }
                if (driveConnected) {
                    TextButton(onClick = onUpload) { Text("Drive", color = UiPalette.ink) }
                }
                TextButton(onClick = { deleteDialog = true }) { Text("Delete", color = UiPalette.danger) }
            }
        }
    }

    if (shareDialog) {
        AlertDialog(
            onDismissRequest = { shareDialog = false },
            title = { Text("Share document", color = UiPalette.ink) },
            text = { Text("Choose PDF or JPG.", color = UiPalette.muted) },
            confirmButton = {
                Row {
                    TextButton(onClick = { shareDialog = false; onShare(OutputFormat.PDF) }) { Text("PDF") }
                    TextButton(onClick = { shareDialog = false; onShare(OutputFormat.JPG) }) { Text("JPG") }
                }
            },
            dismissButton = { TextButton(onClick = { shareDialog = false }) { Text("Cancel") } }
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete this scan?", color = UiPalette.ink) },
            text = {
                Text(
                    "The local PDF, page images and OCR index will be removed.",
                    color = UiPalette.muted
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteDialog = false; onDelete() }) {
                    Text("Delete", color = UiPalette.danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun FoldersScreen(
    docs: List<ScanDoc>,
    folders: List<ScanFolder>,
    tags: List<ScanTag>,
    onAddFolder: (String) -> Unit,
    onAddTag: (String) -> Unit
) {
    var addingFolder by remember { mutableStateOf(false) }
    var addingTag by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Folders & tags", color = UiPalette.ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("Organize scans around the way you work.", color = UiPalette.muted, fontSize = 13.sp)
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Folders",
                    color = UiPalette.ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { addingFolder = true }) { Text("+ New folder") }
            }
        }

        if (folders.isEmpty()) {
            item { SmallEmpty("No folders yet. Create folders for receipts, contracts, clients or projects.") }
        } else {
            items(folders, key = { it.id }) { folder ->
                val count = docs.count { it.folderId == folder.id }
                Surface(
                    color = UiPalette.surface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(folder.color).copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("■", color = Color(folder.color))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, color = UiPalette.ink, fontWeight = FontWeight.Bold)
                            Text(
                                count.toString() + " document" + if (count == 1) "" else "s",
                                color = UiPalette.muted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Color tags",
                    color = UiPalette.ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { addingTag = true }) { Text("+ New tag") }
            }
        }

        if (tags.isEmpty()) {
            item { SmallEmpty("Add color tags such as Tax, Paid, Urgent or Client A.") }
        } else {
            items(tags, key = { it.name }) { tag ->
                Surface(
                    color = UiPalette.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("●", color = Color(tag.color), fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(tag.name, color = UiPalette.ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (addingFolder) {
        NameDialog("New folder", "Folder name", { addingFolder = false }) {
            onAddFolder(it)
            addingFolder = false
        }
    }
    if (addingTag) {
        NameDialog("New color tag", "Tag name", { addingTag = false }) {
            onAddTag(it)
            addingTag = false
        }
    }
}

@Composable
fun SmallEmpty(text: String) {
    Surface(
        color = UiPalette.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, color = UiPalette.muted, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    onConnectDrive: () -> Unit,
    onOpenDrive: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        item {
            Text("Settings", color = UiPalette.ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("Choose how scans are saved, cleaned and shared.", color = UiPalette.muted, fontSize = 13.sp)
        }

        item { SettingsHeader("Default export") }
        item {
            SettingsCard {
                Text("File format", color = UiPalette.ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    choices = listOf(OutputFormat.PDF to "PDF", OutputFormat.JPG to "JPG"),
                    selected = settings.defaultFormat,
                    onSelect = { onSettings(settings.copy(defaultFormat = it)) }
                )
            }
        }

        item { SettingsHeader("Image") }
        item {
            SettingsCard {
                Text("Default color mode", color = UiPalette.ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    choices = listOf(
                        ColorMode.COLOR to "Color",
                        ColorMode.GRAYSCALE to "Grayscale",
                        ColorMode.BLACK_WHITE to "B&W"
                    ),
                    selected = settings.defaultColorMode,
                    onSelect = { onSettings(settings.copy(defaultColorMode = it)) }
                )
                Spacer(Modifier.height(14.dp))
                SettingSwitch(
                    title = "Smart shadow & glare cleanup",
                    subtitle = "On-device luminance normalization reduces uneven shadows and harsh highlights.",
                    checked = settings.smartCleanup,
                    onChecked = { onSettings(settings.copy(smartCleanup = it)) }
                )
                if (settings.smartCleanup) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Cleanup strength · " + settings.cleanupStrength.toString() + "%",
                        color = UiPalette.muted,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = settings.cleanupStrength.toFloat(),
                        onValueChange = { onSettings(settings.copy(cleanupStrength = it.toInt())) },
                        valueRange = 20f..100f
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "JPG quality · " + settings.jpegQuality.toString() + "%",
                    color = UiPalette.muted,
                    fontSize = 12.sp
                )
                Slider(
                    value = settings.jpegQuality.toFloat(),
                    onValueChange = { onSettings(settings.copy(jpegQuality = it.toInt())) },
                    valueRange = 80f..100f
                )
            }
        }

        item { SettingsHeader("Text & file names") }
        item {
            SettingsCard {
                SettingSwitch(
                    title = "On-device OCR",
                    subtitle = "Makes document text searchable without sending scans to an app server.",
                    checked = settings.ocrEnabled,
                    onChecked = { onSettings(settings.copy(ocrEnabled = it)) }
                )
                HorizontalDivider(color = Color(0xFF2A3040), modifier = Modifier.padding(vertical = 10.dp))
                SettingSwitch(
                    title = "Contextual auto-naming",
                    subtitle = "Uses detected document type and visible content instead of Scan_Date.",
                    checked = settings.smartNaming,
                    onChecked = { onSettings(settings.copy(smartNaming = it)) }
                )
            }
        }

        item { SettingsHeader("Google Drive") }
        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (settings.driveTreeUri.isBlank()) "Drive folder not connected" else "Drive folder connected",
                            color = UiPalette.ink,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (settings.driveTreeUri.isBlank())
                                "1. Open Google Drive and sign in. 2. Return here and choose a Drive folder."
                            else
                                "A Drive folder is selected. SCANTANTRA stores folder access, never your Google password.",
                            color = UiPalette.muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenDrive) { Text("Open Drive") }
                    TextButton(onClick = onConnectDrive) {
                        Text(if (settings.driveTreeUri.isBlank()) "Choose Drive folder" else "Change folder")
                    }
                }
                HorizontalDivider(color = Color(0xFF2A3040), modifier = Modifier.padding(vertical = 10.dp))
                SettingSwitch(
                    title = "Auto-save completed scans",
                    subtitle = "Automatically writes the selected PDF or JPG output to your chosen Drive folder.",
                    checked = settings.autoDriveUpload,
                    enabled = settings.driveTreeUri.isNotBlank(),
                    onChecked = { onSettings(settings.copy(autoDriveUpload = it)) }
                )
            }
        }

        item { SettingsHeader("Sharing") }
        item {
            SettingsCard {
                SettingSwitch(
                    title = "Smart email subject",
                    subtitle = "Email shares use the contextual scan name as the subject line.",
                    checked = settings.smartEmailSubject,
                    onChecked = { onSettings(settings.copy(smartEmailSubject = it)) }
                )
            }
        }

        item { SettingsHeader("Privacy") }
        item {
            SettingsCard {
                Text("Zero-data by default", color = UiPalette.ink, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Scans, OCR text, folders and tags live in app-private storage. Files leave the device only when you share them or enable your own Drive destination.",
                    color = UiPalette.muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
fun SettingsHeader(text: String) {
    Text(
        text.uppercase(Locale.US),
        color = UiPalette.accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = UiPalette.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) UiPalette.ink else UiPalette.muted,
                fontWeight = FontWeight.SemiBold
            )
            Text(subtitle, color = UiPalette.muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
fun <T> ChoiceRow(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { pair ->
            FilterChip(
                selected = selected == pair.first,
                onClick = { onSelect(pair.first) },
                label = { Text(pair.second) }
            )
        }
    }
}

@Composable
fun ScanOptionsDialog(
    initialBatch: Boolean,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onStart: (Boolean, OutputFormat, ColorMode) -> Unit
) {
    var batch by remember { mutableStateOf(initialBatch) }
    var format by remember { mutableStateOf(settings.defaultFormat) }
    var mode by remember { mutableStateOf(settings.defaultColorMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New scan", color = UiPalette.ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Capture mode", color = UiPalette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                ChoiceRow(
                    choices = listOf(false to "Single page", true to "Batch"),
                    selected = batch,
                    onSelect = { batch = it }
                )

                Spacer(Modifier.height(16.dp))
                Text("Save / share as", color = UiPalette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                ChoiceRow(
                    choices = listOf(OutputFormat.PDF to "PDF", OutputFormat.JPG to "JPG"),
                    selected = format,
                    onSelect = { format = it }
                )

                Spacer(Modifier.height(16.dp))
                Text("Color", color = UiPalette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                ChoiceRow(
                    choices = listOf(
                        ColorMode.COLOR to "Color",
                        ColorMode.GRAYSCALE to "Grayscale",
                        ColorMode.BLACK_WHITE to "B&W"
                    ),
                    selected = mode,
                    onSelect = { mode = it }
                )

                if (settings.smartCleanup) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Smart shadow/glare cleanup will be applied after capture.",
                        color = UiPalette.muted,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(batch, format, mode) }) {
                Text(if (batch) "Start batch scan" else "Scan page")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun OrganizeDialog(
    doc: ScanDoc,
    folders: List<ScanFolder>,
    tags: List<ScanTag>,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit
) {
    var folderId by remember(doc.id) { mutableStateOf(doc.folderId) }
    val selectedTags = remember(doc.id) {
        mutableStateListOf<String>().apply { addAll(doc.tags) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Organize", color = UiPalette.ink) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 430.dp)) {
                item {
                    Text("Folder", color = UiPalette.muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = folderId.isBlank(), onClick = { folderId = "" })
                        Text("Unfiled", color = UiPalette.ink)
                    }
                }
                items(folders, key = { it.id }) { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = folderId == folder.id,
                            onClick = { folderId = folder.id }
                        )
                        Text(folder.name, color = UiPalette.ink)
                    }
                }
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("Tags", color = UiPalette.muted, fontSize = 12.sp)
                }
                items(tags, key = { it.name }) { tag ->
                    val selected = selectedTags.contains(tag.name)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                if (it) selectedTags.add(tag.name) else selectedTags.remove(tag.name)
                            }
                        )
                        Text(tag.name, color = Color(tag.color), fontWeight = FontWeight.SemiBold)
                    }
                }
                if (tags.isEmpty()) {
                    item {
                        Text("Create tags from the Folders tab.", color = UiPalette.muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(folderId, selectedTags.toList()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun NameDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = UiPalette.ink) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(placeholder) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = UiPalette.ink,
                    unfocusedTextColor = UiPalette.ink,
                    focusedBorderColor = UiPalette.accent,
                    unfocusedBorderColor = Color(0xFF3A4254),
                    focusedLabelColor = UiPalette.accent,
                    unfocusedLabelColor = UiPalette.muted
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { if (value.isNotBlank()) onSave(value) },
                enabled = value.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
