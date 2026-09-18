package in.familyconnect.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import in.familyconnect.app.data.DeviceRepository
import in.familyconnect.app.model.AppScreen
import in.familyconnect.app.model.DeviceSnapshot
import in.familyconnect.app.model.MemberUi
import in.familyconnect.app.tracking.LocationTrackingService
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyConnectApp() {
    val context = LocalContext.current
    var screenName by rememberSaveable { mutableStateOf(AppScreen.Home.name) }
    val screen = AppScreen.valueOf(screenName)
    var selectedMember by remember { mutableStateOf<MemberUi?>(null) }
    var snapshot by remember { mutableStateOf(DeviceRepository.snapshot(context)) }
    var trackingEnabled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = DeviceRepository.snapshot(context)
            delay(2000)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startTrackingService(context)
            trackingEnabled = true
        }
    }

    val requestTracking: () -> Unit = {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine) {
            startTrackingService(context)
            trackingEnabled = true
        } else {
            val req = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) req += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(req.toTypedArray())
        }
    }

    val stopTracking: () -> Unit = {
        context.stopService(Intent(context, LocationTrackingService::class.java))
        trackingEnabled = false
    }

    val members = remember(snapshot, trackingEnabled) {
        listOf(
            MemberUi(
                id = "me", name = "Shashank", initial = "S",
                place = if (trackingEnabled) "Live tracking" else "This device",
                detail = snapshot.locationAgeSeconds?.let { if (it < 60) "Updated " + it + "s ago" else "Updated " + (it / 60) + "m ago" } ?: "Location not started",
                battery = snapshot.battery, speed = snapshot.speedKmh.takeIf { it > 0 }, isOnline = snapshot.network != "Offline"
            ),
            MemberUi("k", "Karishma", "K", "Home", "Since 12:48 PM", 72, isPreview = true),
            MemberUi("f", "Father", "F", "Travelling", "8.2 km from Home", 38, speed = 42, eta = "18 min", isPreview = true),
            MemberUi("m", "Mother", "M", "Home", "Since 11:20 AM", 64, isPreview = true)
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = { PremiumBottomBar(selected = screen) { screenName = it.name } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                AppScreen.Home -> HomeScreen(members, snapshot, trackingEnabled,
                    onTrackingToggle = { if (trackingEnabled) stopTracking() else requestTracking() },
                    onMember = { selectedMember = it },
                    onMap = { screenName = AppScreen.Map.name },
                    onSafety = { screenName = AppScreen.Safety.name })
                AppScreen.Map -> FamilyMapScreen(members, snapshot, trackingEnabled, requestTracking) { selectedMember = it }
                AppScreen.Family -> FamilyScreen(members)
                AppScreen.Safety -> SafetyScreen(snapshot, requestTracking)
                AppScreen.Profile -> ProfileScreen(snapshot, trackingEnabled) {
                    if (trackingEnabled) stopTracking() else requestTracking()
                }
            }
        }
    }

    selectedMember?.let {
        MemberStatusSheet(it, snapshot, onDismiss = { selectedMember = null }, onLocate = requestTracking)
    }
}

private fun startTrackingService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
}

@Composable
private fun HomeScreen(
    members: List<MemberUi>,
    snapshot: DeviceSnapshot,
    trackingEnabled: Boolean,
    onTrackingToggle: () -> Unit,
    onMember: (MemberUi) -> Unit,
    onMap: () -> Unit,
    onSafety: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("Family Connect", "Private family circle") }
        item { PreviewNotice() }
        item { FamilyHero(members, onMap, trackingEnabled, onTrackingToggle) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Modifier.weight(1f), Icons.Default.Route, "Start trip", "Share ETA", PurpleSoft, Purple, onMap)
                QuickAction(Modifier.weight(1f), Icons.Default.HealthAndSafety, "Safety", "Check-in & SOS", RoseSoft, Rose, onSafety)
            }
        }
        item { SectionTitle("Everyone’s status", "At a glance") }
        items(members, key = { it.id }) { member -> MemberCard(member) { onMember(member) } }
        item { PermissionHealthCompact(snapshot) }
        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun PreviewNotice() {
    Surface(shape = RoundedCornerShape(16.dp), color = PurpleSoft.copy(alpha = 0.72f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = Purple, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Text(
                "V1 preview • this device is live; other family profiles are sample UI until the secure family backend is connected.",
                fontSize = 12.sp, lineHeight = 16.sp, color = Ink.copy(alpha = .82f)
            )
        }
    }
}

@Composable
private fun AppHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).background(Brush.linearGradient(listOf(Purple, Color(0xFF887FFF))), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.FamilyRestroom, null, tint = Color.White, modifier = Modifier.size(25.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, fontSize = 12.sp, color = Muted)
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Icon(Icons.Default.NotificationsNone, null, modifier = Modifier.padding(10.dp).size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun FamilyHero(members: List<MemberUi>, onMap: () -> Unit, trackingEnabled: Boolean, onTrackingToggle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(26.dp), ambientColor = Purple.copy(alpha = .16f)),
        shape = RoundedCornerShape(26.dp), color = Color.Transparent
    ) {
        Column(
            Modifier.background(
                Brush.linearGradient(listOf(Color(0xFF17182E), Color(0xFF29265A), Color(0xFF4A43A8))),
                RoundedCornerShape(26.dp)
            ).padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FAMILY NOW", color = Color(0xFFBAB7EB), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.3.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("Everyone, one glance.", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                    Text("Know arrivals, journeys and phone status without repeated calls.", color = Color(0xFFD7D6EA), fontSize = 12.sp, lineHeight = 17.sp)
                }
                Box(Modifier.size(62.dp).background(Color.White.copy(alpha=.1f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LocationOn, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    members.take(4).forEachIndexed { index, m ->
                        Box(
                            Modifier.offset(x = (-7 * index).dp).size(37.dp).background(avatarColor(m.id), CircleShape)
                                .border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Text(m.initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                }
                FilledTonalButton(
                    onClick = onTrackingToggle,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha=.13f), contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(if (trackingEnabled) Icons.Default.GpsFixed else Icons.Default.MyLocation, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (trackingEnabled) "Live on" else "Go live", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onMap,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF25234F)),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Open map", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun QuickAction(modifier: Modifier, icon: ImageVector, title: String, subtitle: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.55f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(bg, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = Muted, fontSize = 10.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 11.sp, color = Muted) }
    }
}

@Composable
private fun MemberCard(member: MemberUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).background(avatarColor(member.id), CircleShape), contentAlignment = Alignment.Center) {
                    Text(member.initial, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(13.dp).background(if (member.isOnline) Mint else Color(0xFFA7A8B5), CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(member.name, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        if (member.isPreview) {
                            Spacer(Modifier.width(7.dp))
                            StatusPill("Preview", PurpleSoft, Purple)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(member.place + " • " + member.detail, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(13.dp))
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.65f), RoundedCornerShape(15.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniMetric(Icons.Default.Battery5Bar, member.battery.toString() + "%", "Battery")
                if (member.speed != null) MiniMetric(Icons.Default.Speed, member.speed.toString() + " km/h", "Speed")
                else MiniMetric(Icons.Default.Schedule, if (member.isOnline) "Recent" else "Stale", "Update")
                MiniMetric(Icons.Default.Navigation, member.eta ?: if (member.place == "Home") "Home" else "—", "ETA")
            }
        }
    }
}

@Composable
private fun MiniMetric(icon: ImageVector, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = Purple)
        Spacer(Modifier.width(5.dp))
        Column {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
            Text(label, color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun PermissionHealthCompact(snapshot: DeviceSnapshot) {
    val context = LocalContext.current
    val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val notifications = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
    Surface(
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(if (location && notifications) MintSoft else AmberSoft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VerifiedUser, null, tint = if (location && notifications) Mint else Amber, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Permission health", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(if (location && notifications) "Core safety permissions look good" else "Some features need attention", color = Muted, fontSize = 11.sp)
                }
                StatusPill(if (location && notifications) "Healthy" else "Review", if (location && notifications) MintSoft else AmberSoft, if (location && notifications) Mint else Amber)
            }
            if (!snapshot.usageAccess) {
                Spacer(Modifier.height(10.dp))
                Text("App-usage sharing is OFF — exactly as required until you explicitly enable it.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FamilyMapScreen(
    members: List<MemberUi>,
    snapshot: DeviceSnapshot,
    trackingEnabled: Boolean,
    onStartTracking: () -> Unit,
    onMember: (MemberUi) -> Unit
) {
    val context = LocalContext.current
    Configuration.getInstance().userAgentValue = context.packageName
    val center = if (snapshot.latitude != null && snapshot.longitude != null) GeoPoint(snapshot.latitude, snapshot.longitude) else GeoPoint(26.8467, 80.9462)

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                MapView(it).apply {
                    setMultiTouchControls(true)
                    controller.setZoom(13.5)
                    controller.setCenter(center)
                }
            },
            update = { map ->
                map.overlays.removeAll { it is Marker }
                val points = mutableListOf<Pair<MemberUi, GeoPoint>>()
                members.firstOrNull()?.let { me ->
                    if (snapshot.latitude != null && snapshot.longitude != null) points += me to GeoPoint(snapshot.latitude, snapshot.longitude)
                }
                val preview = members.filter { it.isPreview }
                if (preview.isNotEmpty()) points += preview[0] to GeoPoint(26.8562, 80.9490)
                if (preview.size > 1) points += preview[1] to GeoPoint(26.8326, 80.9361)
                if (preview.size > 2) points += preview[2] to GeoPoint(26.8674, 80.9257)
                points.forEach { pair ->
                    val m = pair.first
                    val p = pair.second
                    map.overlays.add(Marker(map).apply {
                        position = p
                        title = if (m.isPreview) m.name + " • preview" else m.name
                        subDescription = m.place
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                if (snapshot.latitude != null && snapshot.longitude != null) map.controller.animateTo(center)
                map.invalidate()
            }
        )

        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha=.95f), shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Map, null, tint = Purple)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Live family map", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Text(if (snapshot.latitude != null) "Your live device location is available" else "Start live location to place this device on map", color = Muted, fontSize = 11.sp)
                    }
                    FilledTonalButton(onClick = onStartTracking, shape = RoundedCornerShape(12.dp)) {
                        Icon(if (trackingEnabled) Icons.Default.GpsFixed else Icons.Default.MyLocation, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (trackingEnabled) "Live" else "Locate", fontSize = 11.sp)
                    }
                }
            }
            if (snapshot.latitude == null) {
                Surface(color = AmberSoft.copy(alpha=.94f), shape = RoundedCornerShape(16.dp)) {
                    Text("Map is centred on Lucknow for preview. Grant location to show this phone’s real position.", Modifier.padding(12.dp), fontSize = 11.sp, color = Color(0xFF76501B))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 150.dp),
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(members) { m ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onMember(m) },
                    color = MaterialTheme.colorScheme.surface.copy(alpha=.96f), shape = RoundedCornerShape(18.dp), shadowElevation = 5.dp
                ) {
                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(avatarColor(m.id), CircleShape), contentAlignment = Alignment.Center) {
                            Text(m.initial, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(m.place + " • " + m.detail, color = Muted, fontSize = 10.5.sp, maxLines = 1)
                        }
                        if (m.speed != null) StatusPill(m.speed.toString() + " km/h", PurpleSoft, Purple)
                    }
                }
            }
        }
    }
}

@Composable
private fun FamilyScreen(members: List<MemberUi>) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf(generateCode()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("Patel Family", members.size.toString() + " member profiles") }
        item {
            Surface(
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))
            ) {
                Column(Modifier.padding(17.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(43.dp).background(PurpleSoft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PersonAdd, null, tint = Purple)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Invite family member", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text("Temporary pairing code • preview flow", color = Muted, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(code, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { code = generateCode() }) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Join my Family Connect circle with invitation code " + code)
                            }
                            context.startActivity(Intent.createChooser(share, "Share invitation"))
                        }) { Icon(Icons.Default.Share, "Share") }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text("Production pairing must be backed by OTP + expiring server-side invitations before this is used across devices.", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
                }
            }
        }
        item { SectionTitle("Family members", "Consent-controlled") }
        items(members) { member ->
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(avatarColor(member.id), CircleShape), contentAlignment = Alignment.Center) {
                        Text(member.initial, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(member.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(if (member.id == "me") "Owner • this device" else "Family member • sample profile", color = Muted, fontSize = 10.5.sp)
                    }
                    StatusPill(if (member.isOnline) "Active" else "Offline", if (member.isOnline) MintSoft else MaterialTheme.colorScheme.surfaceVariant, if (member.isOnline) Mint else Muted)
                }
            }
        }
        item {
            InfoCard(Icons.Default.Security, "Adult privacy stays authoritative",
                "The Family Owner can manage the Circle but cannot silently override another adult member’s sharing permissions.",
                PurpleSoft, Purple)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SafetyScreen(snapshot: DeviceSnapshot, onStartTracking: () -> Unit) {
    var sosActive by rememberSaveable { mutableStateOf(false) }
    var checkIn by rememberSaveable { mutableStateOf(false) }
    var expected by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("Safety Centre", "Check-in, journey watch and SOS") }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(), color = if (sosActive) RoseSoft else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, if (sosActive) Rose.copy(alpha=.35f) else MaterialTheme.colorScheme.outline.copy(alpha=.5f))
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(128.dp).shadow(18.dp, CircleShape, ambientColor = Rose.copy(alpha=.22f))
                            .background(if (sosActive) Color(0xFFD93651) else Rose, CircleShape)
                            .combinedClickable(onClick = { }, onLongClick = {
                                sosActive = !sosActive
                                if (sosActive) onStartTracking()
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (sosActive) Icons.Default.StopCircle else Icons.Default.Sos, null, tint = Color.White, modifier = Modifier.size(42.dp))
                            Text(if (sosActive) "END SOS" else "SOS", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    Text(if (sosActive) "SOS live session active on this device" else "Long-press the SOS button", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text(
                        if (sosActive) "High-frequency location is active. Server delivery to selected family contacts requires backend connection."
                        else "Designed to start live location and notify selected family contacts after confirmation.",
                        color = Muted, fontSize = 11.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (sosActive) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill("Battery " + snapshot.battery + "%", RoseSoft, Rose)
                            StatusPill(snapshot.network, PurpleSoft, Purple)
                            StatusPill(snapshot.speedKmh.toString() + " km/h", MintSoft, Mint)
                        }
                    }
                }
            }
        }
        item { SectionTitle("Safety tools") }
        item {
            SafetyAction(Icons.Default.HowToReg, "Check on me",
                if (checkIn) "Check-in scheduled • tap to cancel" else "Schedule a family check-in",
                Purple, PurpleSoft, checkIn) { checkIn = !checkIn }
        }
        item {
            SafetyAction(Icons.Default.Schedule, "Expected arrival",
                if (expected) "Expected arrival is being watched" else "Tell family when you expect to arrive",
                Amber, AmberSoft, expected) { expected = !expected }
        }
        item {
            SafetyAction(Icons.Default.Route, "Watch my journey", "Temporary live trip sharing until arrival", Mint, MintSoft, false) {
                onStartTracking()
            }
        }
        item {
            InfoCard(Icons.Default.PrivacyTip, "No hidden monitoring",
                "Safety sessions are visible, revocable and tied to Android permissions. The app does not attempt to bypass disabled system location.",
                MintSoft, Mint)
        }
    }
}

@Composable
private fun SafetyAction(icon: ImageVector, title: String, subtitle: String, color: Color, bg: Color, active: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(21.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(bg, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                Text(subtitle, color = Muted, fontSize = 10.5.sp)
            }
            if (active) StatusPill("Active", MintSoft, Mint) else Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun ProfileScreen(snapshot: DeviceSnapshot, trackingEnabled: Boolean, onTrackingToggle: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var batteryShare by rememberSaveable { mutableStateOf(prefs.getBoolean("share_battery", true)) }
    var speedShare by rememberSaveable { mutableStateOf(prefs.getBoolean("share_speed", true)) }
    var historyShare by rememberSaveable { mutableStateOf(prefs.getBoolean("share_history", false)) }
    var usageShare by rememberSaveable { mutableStateOf(prefs.getBoolean("share_usage", false)) }
    var speedLimit by rememberSaveable { mutableStateOf(prefs.getInt("speed_limit", 80)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("Privacy Centre", "You stay in control") }
        item {
            Surface(
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).background(avatarColor("me"), CircleShape), contentAlignment = Alignment.Center) {
                        Text("S", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("My sharing", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Text("This phone • " + snapshot.network + " • " + snapshot.battery + "%", color = Muted, fontSize = 11.sp)
                    }
                    StatusPill(if (trackingEnabled) "Location live" else "Location paused", if (trackingEnabled) MintSoft else AmberSoft, if (trackingEnabled) Mint else Amber)
                }
            }
        }

        item { SectionTitle("What I share", "Granular controls") }
        item {
            SettingsGroup {
                SettingSwitch(Icons.Default.LocationOn, "Live location", "Foreground location sharing", trackingEnabled) { onTrackingToggle() }
                DividerSoft()
                SettingSwitch(Icons.Default.Speed, "Driving speed", "Share validated speed during active tracking", speedShare) {
                    speedShare = it; prefs.edit().putBoolean("share_speed", it).apply()
                }
                DividerSoft()
                SettingSwitch(Icons.Default.BatteryChargingFull, "Battery & charging", "Share battery status with authorised family", batteryShare) {
                    batteryShare = it; prefs.edit().putBoolean("share_battery", it).apply()
                }
                DividerSoft()
                SettingSwitch(Icons.Default.History, "Location history", "Store journey/timeline events locally in this preview", historyShare) {
                    historyShare = it; prefs.edit().putBoolean("share_history", it).apply()
                }
                DividerSoft()
                SettingSwitch(Icons.Default.Apps, "App activity", if (snapshot.usageAccess) "Usage Access granted • explicit sharing toggle" else "Usage Access is not granted", usageShare) {
                    usageShare = it
                    prefs.edit().putBoolean("share_usage", it).apply()
                    if (it && !snapshot.usageAccess) {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }

        item { SectionTitle("Speed alert threshold") }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Family alert threshold", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        StatusPill(speedLimit.toString() + " km/h", PurpleSoft, Purple)
                    }
                    Slider(
                        value = speedLimit.toFloat(),
                        onValueChange = { speedLimit = (it / 5).toInt() * 5 },
                        onValueChangeFinished = { prefs.edit().putInt("speed_limit", speedLimit).apply() },
                        valueRange = 40f..140f, steps = 19
                    )
                    Text("This is a family alert threshold, not a statement of the legal speed limit.", color = Muted, fontSize = 10.5.sp)
                }
            }
        }

        item { SectionTitle("Permission health") }
        item { PermissionHealthFull(snapshot) }
        item { SectionTitle("Data & access") }
        item {
            SettingsGroup {
                NavigationSetting(Icons.Default.PeopleAlt, "Who can see my information?", "Per-member access matrix") { }
                DividerSoft()
                NavigationSetting(Icons.Default.PauseCircle, "Pause sharing", "15 min • 1 hour • until tomorrow") { }
                DividerSoft()
                NavigationSetting(Icons.Default.DeleteOutline, "Delete my location history", "Retention & deletion controls") { }
            }
        }
        item {
            InfoCard(Icons.Default.Lock, "Privacy architecture",
                "This preview contains no hidden microphone, camera, message or call-content collection. Multi-device authorization must be enforced server-side before production launch.",
                PurpleSoft, Purple)
        }
    }
}

@Composable
private fun PermissionHealthFull(snapshot: DeviceSnapshot) {
    val context = LocalContext.current
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = if (Build.VERSION.SDK_INT >= 29) ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED else true
    val notifications = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true

    Surface(shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))) {
        Column(Modifier.padding(15.dp)) {
            HealthRow("Precise location", fine, if (fine) "Granted" else "Needed for live map")
            DividerSoft()
            HealthRow("Background location", background, if (background) "Granted" else "Needed for reliable arrivals")
            DividerSoft()
            HealthRow("Notifications", notifications, if (notifications) "Granted" else "Needed for alerts")
            DividerSoft()
            HealthRow("Usage Access", snapshot.usageAccess, if (snapshot.usageAccess) "Granted" else "Optional • currently off")
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.BuildCircle, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Open app permissions")
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, good: Boolean, detail: String) {
    Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (good) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = if (good) Mint else Amber, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Text(detail, color = Muted, fontSize = 10.5.sp)
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.5f))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp), content = content)
    }
}

@Composable
private fun SettingSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Purple, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(subtitle, color = Muted, fontSize = 9.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun NavigationSetting(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Purple, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Text(subtitle, color = Muted, fontSize = 9.8.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun DividerSoft() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha=.45f))
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(21.dp), color = bg.copy(alpha=.72f)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(38.dp).background(Color.White.copy(alpha=.55f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp, color = Ink)
                Spacer(Modifier.height(3.dp))
                Text(body, fontSize = 10.5.sp, color = Ink.copy(alpha=.72f), lineHeight = 14.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberStatusSheet(member: MemberUi, snapshot: DeviceSnapshot, onDismiss: () -> Unit, onLocate: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).background(avatarColor(member.id), CircleShape), contentAlignment = Alignment.Center) {
                    Text(member.initial, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(member.name, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        if (member.isPreview) {
                            Spacer(Modifier.width(7.dp))
                            StatusPill("Preview", PurpleSoft, Purple)
                        }
                    }
                    Text(member.place + " • " + member.detail, color = Muted, fontSize = 11.sp)
                }
                StatusPill(if (member.isOnline) "Online" else "Stale", if (member.isOnline) MintSoft else AmberSoft, if (member.isOnline) Mint else Amber)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard(Modifier.weight(1f), "Battery", member.battery.toString() + "%", Icons.Default.Battery5Bar)
                val displaySpeed = member.speed ?: if (member.id == "me") snapshot.speedKmh else 0
                MetricCard(Modifier.weight(1f), "Speed", displaySpeed.toString() + " km/h", Icons.Default.Speed)
                MetricCard(Modifier.weight(1f), "ETA", member.eta ?: "—", Icons.Default.Schedule)
            }
            Spacer(Modifier.height(14.dp))
            if (member.id == "me" && snapshot.latitude != null && snapshot.longitude != null) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Current device location", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(
                            "%.5f".format(snapshot.latitude) + ", " + "%.5f".format(snapshot.longitude) +
                                " • updated " + (snapshot.locationAgeSeconds ?: 0) + "s ago",
                            color = Muted, fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = onLocate, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.MyLocation, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Locate")
                }
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Message, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Message")
                }
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Route, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Watch")
                }
            }
            if (member.isPreview) {
                Spacer(Modifier.height(10.dp))
                Text("This member card is sample UI in the preview APK. It is not presenting a real person’s location or device status.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier, label: String, value: String, icon: ImageVector) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(11.dp)) {
            Icon(icon, null, tint = Purple, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(7.dp))
            Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            Text(label, color = Muted, fontSize = 9.5.sp)
        }
    }
}

@Composable
private fun StatusPill(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = CircleShape) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = fg, fontWeight = FontWeight.Bold, fontSize = 9.5.sp)
    }
}

@Composable
private fun PremiumBottomBar(selected: AppScreen, onSelect: (AppScreen) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 10.dp) {
        NavigationBar(
            modifier = Modifier.navigationBarsPadding().height(72.dp),
            containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp
        ) {
            val navItems = listOf(
                Triple(AppScreen.Home, Icons.Default.Home, "Home"),
                Triple(AppScreen.Map, Icons.Default.Map, "Map"),
                Triple(AppScreen.Family, Icons.Default.Groups, "Family"),
                Triple(AppScreen.Safety, Icons.Default.HealthAndSafety, "Safety"),
                Triple(AppScreen.Profile, Icons.Default.Person, "Profile")
            )
            navItems.forEach { item ->
                val s = item.first
                NavigationBarItem(
                    selected = selected == s, onClick = { onSelect(s) },
                    icon = { Icon(item.second, item.third, modifier = Modifier.size(21.dp)) },
                    label = { Text(item.third, fontSize = 9.5.sp, fontWeight = if (selected == s) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Purple, selectedTextColor = Purple, indicatorColor = PurpleSoft,
                        unselectedIconColor = Muted, unselectedTextColor = Muted
                    )
                )
            }
        }
    }
}

private fun avatarColor(id: String): Color = when (id) {
    "me" -> Color(0xFF5F58D9)
    "k" -> Color(0xFFE26D8D)
    "f" -> Color(0xFF2C9C80)
    "m" -> Color(0xFFD28A3C)
    else -> Purple
}

private fun generateCode(): String = Random.nextInt(100, 999).toString() + " " + Random.nextInt(100, 999).toString()
