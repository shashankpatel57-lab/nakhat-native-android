package com.familyconnect.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.familyconnect.app.ExternalDestinationShare
import com.familyconnect.app.MainActivity
import com.familyconnect.app.cloud.CloudEvent
import com.familyconnect.app.cloud.CloudMember
import com.familyconnect.app.cloud.CloudState
import com.familyconnect.app.cloud.FamilyCloud
import com.familyconnect.app.cloud.SharingRule
import com.familyconnect.app.cloud.ShortInvite
import com.familyconnect.app.model.DeviceSnapshot
import com.familyconnect.app.safety.EmergencyAudio
import com.familyconnect.app.state.AppPrefs
import com.familyconnect.app.state.RoadRoute
import com.familyconnect.app.state.SavedPlace
import com.familyconnect.app.tracking.LocationTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.UUID
import kotlin.math.roundToInt

private val V7Blue = Color(0xFF2563EB)
private val V7Cyan = Color(0xFF0891B2)
private val V7Green = Color(0xFF059669)
private val V7Red = Color(0xFFE11D48)
private val V7Ink = Color(0xFF0F172A)
private val V7Muted = Color(0xFF64748B)
private val V7Canvas = Color(0xFFF5F7FB)
private val V7BlueSoft = Color(0xFFEFF6FF)
private val V7GreenSoft = Color(0xFFECFDF5)
private val V7RedSoft = Color(0xFFFFF1F2)
private val V7AmberSoft = Color(0xFFFFFBEB)

@Composable
private fun V7TopBar(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(16.dp), color = V7BlueSoft) {
            Icon(icon, null, tint = V7Blue, modifier = Modifier.padding(11.dp).size(23.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Black, fontSize = 21.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, color = V7Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun V7Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val click = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Surface(
        modifier = click,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f)),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

@Composable
private fun V7Section(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Black, fontSize = 14.sp)
        Text(label, color = V7Muted, fontSize = 8.8.sp)
    }
}

@Composable
private fun QuickAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    background: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = background) {
                Icon(icon, null, tint = tint, modifier = Modifier.padding(9.dp).size(21.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(title, fontWeight = FontWeight.Black, fontSize = 12.5.sp)
            Text(subtitle, color = V7Muted, fontSize = 9.4.sp, lineHeight = 12.sp, maxLines = 2)
        }
    }
}

@Composable
fun HomeDashboard(
    snapshot: DeviceSnapshot,
    cloud: CloudState?,
    cloudBusy: Boolean,
    cloudError: String?,
    trackingEnabled: Boolean,
    onToggleTracking: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenFamily: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedMapText by ExternalDestinationShare.text.collectAsState()

    var showTripChooser by remember { mutableStateOf(false) }
    var routingBusy by remember { mutableStateOf(false) }
    var tripError by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var fullHistory by remember { mutableStateOf<List<CloudEvent>>(emptyList()) }
    var historyBusy by remember { mutableStateOf(false) }
    var resolvedSharedPlace by remember { mutableStateOf<SavedPlace?>(null) }

    val trip = AppPrefs.trip(context)
    val places = cloud?.places ?: AppPrefs.places(context)
    val members = cloud?.members.orEmpty()
    val visibleMembers = members.filter { it.locationVisible }
    val recent = cloud?.events.orEmpty().sortedByDescending { it.createdAt }.take(3)

    fun launchTrip(place: SavedPlace) {
        val lat = snapshot.latitude
        val lon = snapshot.longitude
        if (lat == null || lon == null) {
            if (!trackingEnabled) onToggleTracking()
            tripError = "Waiting for a fresh GPS fix. Turn on location sharing and try again in a few seconds."
            return
        }
        routingBusy = true
        scope.launch {
            val route = withContext(Dispatchers.IO) {
                FamilyCloud.roadRoute(context, lat, lon, place.lat, place.lon)
            }
            route.onSuccess { road ->
                val sync = withContext(Dispatchers.IO) { FamilyCloud.setTripRoute(context, road) }
                if (sync.isSuccess) {
                    AppPrefs.startTrip(context, place, road)
                    ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
                    withContext(Dispatchers.IO) {
                        FamilyCloud.publishEvent(
                            context,
                            "TRIP_STARTED",
                            AppPrefs.profileName(context) + " started a trip",
                            "Going to " + place.name + " • " +
                                (if (road.distanceM < 1000f) road.distanceM.toInt().toString() + " m"
                                else "%.1f km".format(road.distanceM / 1000f)) +
                                " by road • ETA " + ((road.durationS + 59) / 60).coerceAtLeast(1) + " min"
                        )
                    }
                    onRefresh()
                } else {
                    tripError = sync.exceptionOrNull()?.message ?: "Could not share route with family."
                }
            }.onFailure {
                tripError = it.message ?: "Could not calculate the road route."
            }
            routingBusy = false
        }
    }

    LaunchedEffect(sharedMapText) {
        val text = sharedMapText ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { FamilyCloud.resolveMapShare(context, text) }
        result.onSuccess {
            resolvedSharedPlace = SavedPlace(
                id = "shared-" + System.currentTimeMillis(),
                name = it.name,
                lat = it.lat,
                lon = it.lon
            )
        }.onFailure {
            tripError = it.message ?: "Could not read the shared Google Maps destination."
            ExternalDestinationShare.consume()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(V7Canvas),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            V7TopBar(
                AppPrefs.familyName(context),
                "Live family coordination • " + AppPrefs.profileName(context),
                Icons.Default.Hub
            )
        }

        cloudError?.let { message ->
            item {
                Surface(color = V7AmberSoft, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, null, tint = Color(0xFFB45309))
                        Spacer(Modifier.width(8.dp))
                        Text(message, fontSize = 10.5.sp, modifier = Modifier.weight(1f), color = V7Ink)
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.background(
                        Brush.linearGradient(listOf(Color(0xFF0F3D75), Color(0xFF2563EB), Color(0xFF0891B2))),
                        RoundedCornerShape(28.dp)
                    ).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("FAMILY PULSE", color = Color.White.copy(alpha = .72f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (trackingEnabled) "You're sharing live" else "You're private right now",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 21.sp
                        )
                        Text(
                            visibleMembers.size.toString() + " family location" + if (visibleMembers.size == 1) "" else "s" + " available",
                            color = Color.White.copy(alpha = .78f),
                            fontSize = 10.5.sp
                        )
                        Spacer(Modifier.height(13.dp))
                        FilledTonalButton(
                            onClick = onToggleTracking,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.White.copy(alpha = .16f),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(if (trackingEnabled) Icons.Default.PauseCircle else Icons.Default.LocationOn, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (trackingEnabled) "Pause my sharing" else "Share my location", fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(shape = RoundedCornerShape(22.dp), color = Color.White.copy(alpha = .13f)) {
                        Column(Modifier.padding(13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Groups, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(members.size.toString(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                            Text("members", color = Color.White.copy(alpha = .7f), fontSize = 8.5.sp)
                        }
                    }
                }
            }
        }

        if (trip.active) {
            item {
                V7Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = V7BlueSoft) {
                            Icon(Icons.Default.Navigation, null, tint = V7Blue, modifier = Modifier.padding(10.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Active trip", color = V7Muted, fontSize = 9.5.sp)
                            Text(trip.destinationName ?: "Destination", fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text(
                                (trip.remainingM?.let { if (it < 1000f) it.toInt().toString() + " m" else "%.1f km".format(it / 1000f) } ?: "…") +
                                    " by road" + (trip.etaMinutes?.let { " • ETA " + it + " min" } ?: ""),
                                color = V7Blue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp
                            )
                        }
                        TextButton(onClick = onOpenMap) { Text("View map") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp)).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Metric("Current", trip.currentSpeed.toString() + " km/h")
                        Metric("Average", trip.averageSpeed.toString() + " km/h")
                        Metric("Maximum", trip.maxSpeed.toString() + " km/h")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val name = trip.destinationName ?: "destination"
                            AppPrefs.stopTrip(context)
                            scope.launch(Dispatchers.IO) {
                                FamilyCloud.clearTripRoute(context)
                                FamilyCloud.publishEvent(context, "TRIP_ENDED", AppPrefs.profileName(context) + " ended the trip", "Trip to " + name + " was ended.")
                            }
                            onRefresh()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.StopCircle, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("End trip")
                    }
                }
            }
        }

        item { V7Section("Quick actions") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Modifier.weight(1f), Icons.Default.Map, "Live map", "See everyone at a glance", V7Blue, V7BlueSoft, onOpenMap)
                QuickAction(Modifier.weight(1f), Icons.Default.Route, "Start trip", "Share route, speed & ETA", V7Cyan, Color(0xFFECFEFF)) { showTripChooser = true }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Modifier.weight(1f), Icons.Default.PersonAddAlt1, "Family", "Invite, remove & manage", V7Green, V7GreenSoft, onOpenFamily)
                QuickAction(Modifier.weight(1f), Icons.Default.LocationOn, "Places", "Home, office & alerts", Color(0xFF7C3AED), Color(0xFFF5F3FF), onOpenFamily)
            }
        }

        item { V7Section("Family now", members.size.toString() + " people", onOpenFamily) }
        if (members.isEmpty()) {
            item {
                V7Card {
                    Text("No family member yet", fontWeight = FontWeight.Black)
                    Text("Invite someone with a 6-digit code from the Family tab.", color = V7Muted, fontSize = 10.5.sp)
                }
            }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(members, key = { it.id }) { member ->
                        Surface(
                            modifier = Modifier.width(205.dp).clickable(onClick = onOpenMap),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f))
                        ) {
                            Column(Modifier.padding(13.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(38.dp).background(avatarColor(member.id), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(member.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(member.name, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
                                        Text(
                                            if (!member.locationVisible) "Private"
                                            else member.motion + " • " + ageText(member.updatedAt),
                                            color = if (!member.locationVisible) V7Red else V7Muted,
                                            fontSize = 8.8.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Metric("Speed", if (member.speedVisible) member.speed.toString() + " km/h" else "Private")
                                    Metric("Battery", if (member.batteryVisible && member.battery >= 0) member.battery.toString() + "%" else "Private")
                                }
                                if (member.tripActive && member.destinationName != null) {
                                    Spacer(Modifier.height(9.dp))
                                    Surface(color = V7BlueSoft, shape = RoundedCornerShape(12.dp)) {
                                        Text(
                                            "→ " + member.destinationName + (member.etaMinutes?.let { " • " + it + " min" } ?: ""),
                                            Modifier.fillMaxWidth().padding(8.dp),
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = V7Blue,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            V7Section("Recent activity", if (cloud?.events.orEmpty().size > 3) "See all" else null) {
                historyBusy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { FamilyCloud.history(context, 150) }
                    historyBusy = false
                    result.onSuccess {
                        fullHistory = it
                        showHistory = true
                    }.onFailure { tripError = it.message }
                }
            }
        }

        if (recent.isEmpty()) {
            item {
                V7Card {
                    Text("Nothing new yet", fontWeight = FontWeight.Bold)
                    Text("Arrivals, trips, check-ins and safety updates will appear here.", color = V7Muted, fontSize = 10.sp)
                }
            }
        } else {
            items(recent, key = { it.id }) { event ->
                ActivityRow(event)
            }
        }

        if (historyBusy || cloudBusy || routingBusy) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        tripError?.let {
            item {
                Surface(color = V7RedSoft, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = V7Red, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(it, color = V7Ink, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { tripError = null }) { Icon(Icons.Default.Close, "Dismiss") }
                    }
                }
            }
        }
    }

    if (showTripChooser) {
        TripChooser(
            places = places,
            onDismiss = { showTripChooser = false },
            onSaved = {
                showTripChooser = false
                launchTrip(it)
            },
            onGoogleMaps = {
                showTripChooser = false
                val gm = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).apply {
                    setPackage("com.google.android.apps.maps")
                }
                runCatching { context.startActivity(gm) }.onFailure {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")))
                }
            }
        )
    }

    resolvedSharedPlace?.let { place ->
        AlertDialog(
            onDismissRequest = {
                resolvedSharedPlace = null
                ExternalDestinationShare.consume()
            },
            icon = { Icon(Icons.Default.Place, null, tint = V7Blue) },
            title = { Text("Start trip here?", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Text(place.name, fontWeight = FontWeight.Bold)
                    Text("Selected from Google Maps.", color = V7Muted, fontSize = 10.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    resolvedSharedPlace = null
                    ExternalDestinationShare.consume()
                    launchTrip(place)
                }) { Text("Start trip") }
            },
            dismissButton = {
                TextButton(onClick = {
                    resolvedSharedPlace = null
                    ExternalDestinationShare.consume()
                }) { Text("Cancel") }
            }
        )
    }

    if (showHistory) {
        ActivityHistoryDialog(
            events = fullHistory,
            isOwner = AppPrefs.isOwner(context),
            onDismiss = { showHistory = false },
            onClearMine = {
                scope.launch {
                    withContext(Dispatchers.IO) { FamilyCloud.clearHistory(context, false) }
                    val result = withContext(Dispatchers.IO) { FamilyCloud.history(context, 150) }
                    fullHistory = result.getOrDefault(emptyList())
                    onRefresh()
                }
            },
            onClearFamily = {
                scope.launch {
                    withContext(Dispatchers.IO) { FamilyCloud.clearHistory(context, true) }
                    fullHistory = emptyList()
                    onRefresh()
                }
            }
        )
    }
}

@Composable
private fun ActivityRow(event: CloudEvent) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .45f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, bg, fg) = when {
                event.type == "SOS" -> Triple(Icons.Default.Sos, V7RedSoft, V7Red)
                event.type.contains("TRIP") -> Triple(Icons.Default.Route, V7BlueSoft, V7Blue)
                event.type.contains("PLACE") || event.type == "UNSAVED_STOP" -> Triple(Icons.Default.Place, V7GreenSoft, V7Green)
                else -> Triple(Icons.Default.Notifications, Color(0xFFF1F5F9), V7Muted)
            }
            Surface(shape = RoundedCornerShape(12.dp), color = bg) {
                Icon(icon, null, tint = fg, modifier = Modifier.padding(8.dp).size(18.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(event.title, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, maxLines = 1)
                Text(event.memberName + " • " + event.body, color = V7Muted, fontSize = 9.4.sp, maxLines = 2)
            }
            Text(ageText(event.createdAt), color = V7Muted, fontSize = 8.2.sp)
        }
    }
}

@Composable
private fun ActivityHistoryDialog(
    events: List<CloudEvent>,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onClearMine: () -> Unit,
    onClearFamily: () -> Unit
) {
    var confirm by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.88f),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Activity history", fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                Text("The server automatically prunes activity older than 90 days.", color = V7Muted, fontSize = 9.5.sp)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (events.isEmpty()) {
                        item {
                            Text("No activity history.", color = V7Muted, modifier = Modifier.padding(12.dp))
                        }
                    } else {
                        items(events, key = { it.id }) { ActivityRow(it) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirm = "mine" }, modifier = Modifier.weight(1f)) {
                        Text("Clear my activity", fontSize = 10.sp)
                    }
                    if (isOwner) {
                        OutlinedButton(
                            onClick = { confirm = "family" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = V7Red)
                        ) { Text("Clear family feed", fontSize = 10.sp) }
                    }
                }
            }
        }
    }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Clear activity history?") },
            text = { Text(if (which == "family") "This will clear the family activity feed for everyone." else "This will delete activity generated by your account.") },
            confirmButton = {
                Button(onClick = {
                    if (which == "family") onClearFamily() else onClearMine()
                    confirm = null
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TripChooser(
    places: List<SavedPlace>,
    onDismiss: () -> Unit,
    onSaved: (SavedPlace) -> Unit,
    onGoogleMaps: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose destination", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onGoogleMaps),
                    color = V7BlueSoft,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Map, null, tint = V7Blue)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Choose in Google Maps", fontWeight = FontWeight.Black)
                            Text("Pick any place → Share → Family Connect", color = V7Muted, fontSize = 9.5.sp)
                        }
                        Icon(Icons.Default.OpenInNew, null, tint = V7Blue)
                    }
                }

                if (places.isNotEmpty()) {
                    Text("Saved places", color = V7Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    places.take(8).forEach { place ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onSaved(place) },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Place, null, tint = V7Cyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(place.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun LiveFamilyMap(
    snapshot: DeviceSnapshot,
    cloud: CloudState?,
    trackingEnabled: Boolean,
    onStartTracking: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val members = cloud?.members.orEmpty()
    val located = members.filter { it.locationVisible && it.lat != null && it.lon != null }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = located.firstOrNull { it.id == selectedId } ?: located.firstOrNull()

    val routeCache = remember { mutableStateMapOf<String, RoadRoute>() }
    val routeVersionCache = remember { mutableStateMapOf<String, Long>() }

    LaunchedEffect(located.map { it.id }) {
        if (selectedId == null || located.none { it.id == selectedId }) selectedId = located.firstOrNull()?.id
    }

    LaunchedEffect(members.map { Triple(it.id, it.tripActive, it.routeUpdatedAt) }) {
        val activeIds = members.filter { it.tripActive }.map { it.id }.toSet()
        routeCache.keys.toList().filterNot { it in activeIds }.forEach {
            routeCache.remove(it)
            routeVersionCache.remove(it)
        }
        members.filter { it.tripActive && it.routeUpdatedAt > 0L }.forEach { member ->
            if (routeVersionCache[member.id] != member.routeUpdatedAt) {
                val route = withContext(Dispatchers.IO) { FamilyCloud.getTripRoute(context, member.id) }
                route.onSuccess {
                    if (it.points.size >= 2) {
                        routeCache[member.id] = it
                        routeVersionCache[member.id] = member.routeUpdatedAt
                    }
                }
            }
        }
    }

    Configuration.getInstance().userAgentValue = context.packageName
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setUseDataConnection(true)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            minZoomLevel = 4.0
            maxZoomLevel = 20.0
            controller.setZoom(17.0)
            controller.setCenter(
                selected?.let { GeoPoint(it.lat!!, it.lon!!) }
                    ?: snapshot.latitude?.let { GeoPoint(it, snapshot.longitude ?: 0.0) }
                    ?: GeoPoint(26.8467, 80.9462)
            )
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onPause() }
            runCatching { mapView.onDetach() }
        }
    }

    // Only selecting a different member recentres automatically. Movement updates never move the camera.
    LaunchedEffect(selectedId) {
        selected?.let {
            mapView.controller.setZoom(17.0)
            mapView.controller.animateTo(GeoPoint(it.lat!!, it.lon!!))
        }
    }

    fun recenter() {
        selected?.let {
            mapView.controller.setZoom(17.0)
            mapView.controller.animateTo(GeoPoint(it.lat!!, it.lon!!))
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFE9EEF5))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { map ->
                map.overlays.clear()

                located.forEach { member ->
                    val route = routeCache[member.id]
                    if (member.tripActive && route != null && route.points.size >= 2) {
                        val split = nearestRouteIndex(member.lat!!, member.lon!!, route)
                        if (split > 0) {
                            map.overlays.add(
                                Polyline().apply {
                                    setPoints(route.points.take(split + 1).map { GeoPoint(it.lat, it.lon) })
                                    outlinePaint.strokeWidth = 8f
                                    outlinePaint.color = Color(0xFF94A3B8).toArgb()
                                }
                            )
                        }
                        if (split < route.points.lastIndex) {
                            map.overlays.add(
                                Polyline().apply {
                                    setPoints(route.points.drop(split).map { GeoPoint(it.lat, it.lon) })
                                    outlinePaint.strokeWidth = 9f
                                    outlinePaint.color = V7Blue.toArgb()
                                }
                            )
                        }
                    }
                }

                located.forEach { member ->
                    if (member.tripActive && member.destinationLat != null && member.destinationLon != null) {
                        map.overlays.add(
                            Marker(map).apply {
                                position = GeoPoint(member.destinationLat, member.destinationLon)
                                icon = destinationMarkerDrawable(context, member)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = member.destinationName ?: "Destination"
                            }
                        )
                    }
                }

                located.forEach { member ->
                    map.overlays.add(
                        Marker(map).apply {
                            position = GeoPoint(member.lat!!, member.lon!!)
                            icon = liveMemberMarker(context, member, member.id == selectedId)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                selectedId = member.id
                                true
                            }
                        }
                    )
                }
                map.invalidate()
            }
        )

        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
            shadowElevation = 8.dp
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = V7BlueSoft) {
                    Icon(Icons.Default.Navigation, null, tint = V7Blue, modifier = Modifier.padding(9.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("Live map", fontWeight = FontWeight.Black, fontSize = 14.5.sp)
                    Text(
                        if (located.isEmpty()) "No shared location yet"
                        else located.size.toString() + " live • drag freely, recenter only when you choose",
                        color = V7Muted,
                        fontSize = 9.3.sp
                    )
                }
                if (!trackingEnabled) {
                    IconButton(onClick = onStartTracking) { Icon(Icons.Default.LocationOn, "Share my location", tint = V7Blue) }
                }
            }
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { mapView.controller.zoomIn() },
                containerColor = MaterialTheme.colorScheme.surface
            ) { Icon(Icons.Default.Add, "Zoom in") }
            SmallFloatingActionButton(
                onClick = { mapView.controller.zoomOut() },
                containerColor = MaterialTheme.colorScheme.surface
            ) { Icon(Icons.Default.Remove, "Zoom out") }
            FloatingActionButton(
                onClick = { recenter() },
                containerColor = V7Blue,
                contentColor = Color.White,
                modifier = Modifier.size(54.dp)
            ) { Icon(Icons.Default.MyLocation, "Recenter selected") }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (located.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(located, key = { it.id }) { member ->
                        val active = member.id == selectedId
                        Surface(
                            modifier = Modifier.clickable { selectedId = member.id },
                            color = if (active) V7Blue else MaterialTheme.colorScheme.surface.copy(alpha = .97f),
                            contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(14.dp),
                            shadowElevation = 4.dp
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(member.name, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                Spacer(Modifier.width(5.dp))
                                Text("• " + member.speed + " km/h", fontSize = 9.sp)
                            }
                        }
                    }
                }
            }

            selected?.let { member ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .985f),
                    shadowElevation = 12.dp
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(42.dp).background(avatarColor(member.id), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(member.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(member.name, fontWeight = FontWeight.Black, fontSize = 14.5.sp)
                                Text(
                                    member.motion + " • " + ageText(member.updatedAt) +
                                        (member.accuracyM?.let { " • ±" + it.toInt() + " m" } ?: ""),
                                    color = V7Muted,
                                    fontSize = 9.2.sp
                                )
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = V7BlueSoft) {
                                Text(member.speed.toString() + " km/h", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = V7Blue, fontWeight = FontWeight.Black, fontSize = 10.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(9.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Metric("Average", if (member.speedVisible) member.avgSpeed.toString() + " km/h" else "Private")
                            Metric("Maximum", if (member.speedVisible) member.maxSpeed.toString() + " km/h" else "Private")
                            Metric("Battery", if (member.batteryVisible && member.battery >= 0) member.battery.toString() + "%" else "Private")
                            Metric(
                                "Floor",
                                member.floorEstimate?.let { "~" + if (it <= 0) "G" else it.toString() }
                                    ?: member.altitudeM?.let { "%.0f m".format(it) } ?: "—"
                            )
                        }

                        if (member.floorEstimate != null) {
                            Text("Floor is an approximate altitude-based estimate and may be unavailable indoors.", color = V7Muted, fontSize = 8.sp)
                        }

                        if (member.tripActive && member.destinationName != null) {
                            Spacer(Modifier.height(9.dp))
                            Surface(color = V7BlueSoft, shape = RoundedCornerShape(14.dp)) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Flag, null, tint = V7Blue, modifier = Modifier.size(19.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Going to " + member.destinationName, fontWeight = FontWeight.Black, fontSize = 11.5.sp)
                                        Text(
                                            (member.remainingM?.let { if (it < 1000f) it.toInt().toString() + " m" else "%.1f km".format(it / 1000f) } ?: "…") +
                                                " remaining" + (member.etaMinutes?.let { " • ETA " + it + " min" } ?: ""),
                                            color = V7Muted,
                                            fontSize = 9.4.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (located.isEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(30.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LocationSearching, null, tint = V7Blue, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(7.dp))
                    Text("Waiting for live location", fontWeight = FontWeight.Black)
                    Text("Ask a family member to enable location sharing.", color = V7Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun nearestRouteIndex(lat: Double, lon: Double, route: RoadRoute): Int {
    if (route.points.isEmpty()) return 0
    val out = FloatArray(1)
    var best = 0
    var bestDistance = Float.MAX_VALUE
    route.points.forEachIndexed { index, point ->
        android.location.Location.distanceBetween(lat, lon, point.lat, point.lon, out)
        if (out[0] < bestDistance) {
            bestDistance = out[0]
            best = index
        }
    }
    return best.coerceIn(0, route.points.lastIndex)
}

private fun liveMemberMarker(context: Context, member: CloudMember, selected: Boolean): BitmapDrawable {
    val w = 190
    val h = 75
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFAFFFFFF.toInt() }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) V7Blue.toArgb() else 0x22000000
        style = Paint.Style.STROKE
        strokeWidth = if (selected) 4f else 2f
    }
    val rect = RectF(4f, 4f, 184f, 60f)
    canvas.drawRoundRect(rect, 24f, 24f, bg)
    canvas.drawRoundRect(rect, 24f, 24f, border)

    val avatar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = avatarColor(member.id).toArgb() }
    canvas.drawCircle(32f, 31f, 21f, avatar)

    val initial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(member.name.take(1).uppercase(), 32f, 38f, initial)

    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = V7Ink.toArgb()
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
    }
    val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = V7Blue.toArgb()
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText(member.name.take(12), 61f, 27f, namePaint)
    canvas.drawText(member.speed.toString() + " km/h • " + member.motion.take(8), 61f, 47f, infoPaint)

    val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFAFFFFFF.toInt() }
    val path = android.graphics.Path().apply {
        moveTo(84f, 59f); lineTo(96f, 74f); lineTo(108f, 59f); close()
    }
    canvas.drawPath(path, pin)
    return BitmapDrawable(context.resources, bitmap)
}

private fun destinationMarkerDrawable(context: Context, member: CloudMember): BitmapDrawable {
    val w = 176
    val h = 68
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFAFFFFFF.toInt() }
    canvas.drawRoundRect(RectF(4f, 4f, 170f, 55f), 21f, 21f, bg)
    val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = V7Blue.toArgb() }
    canvas.drawCircle(28f, 29f, 18f, circle)
    val flag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("⚑", 28f, 36f, flag)
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = V7Ink.toArgb()
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText((member.destinationName ?: "Destination").take(15), 52f, 25f, title)
    val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = V7Blue.toArgb()
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText((member.etaMinutes?.let { "ETA " + it + " min" } ?: "Destination"), 52f, 43f, sub)
    return BitmapDrawable(context.resources, bitmap)
}

@Composable
fun FamilyAndPlacesScreen(
    snapshot: DeviceSnapshot,
    cloud: CloudState?,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val members = cloud?.members.orEmpty()
    val places = cloud?.places ?: AppPrefs.places(context)
    val myId = AppPrefs.memberId(context)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var invite by remember { mutableStateOf<ShortInvite?>(null) }
    var inviteBusy by remember { mutableStateOf(false) }
    var showAddPlace by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<CloudMember?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(V7Canvas),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { V7TopBar("Family", "Invite people, manage access and places", Icons.Default.Groups) }

        item {
            V7Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(15.dp), color = V7GreenSoft) {
                        Icon(Icons.Default.PersonAddAlt1, null, tint = V7Green, modifier = Modifier.padding(10.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Invite someone", fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text("One-time 6-digit code • valid 10 minutes", color = V7Muted, fontSize = 9.8.sp)
                    }
                    Button(
                        enabled = !inviteBusy,
                        onClick = {
                            inviteBusy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { FamilyCloud.generateShortInvite(context) }
                                inviteBusy = false
                                result.onSuccess { invite = it }.onFailure { error = it.message }
                            }
                        },
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text(if (invite == null) "Generate" else "New code", fontSize = 10.sp)
                    }
                }

                invite?.let { code ->
                    Spacer(Modifier.height(12.dp))
                    Surface(color = V7BlueSoft, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                code.code.chunked(3).joinToString("  "),
                                fontWeight = FontWeight.Black,
                                fontSize = 28.sp,
                                letterSpacing = 3.sp,
                                color = V7Blue,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                clipboard.setPrimaryClip(ClipData.newPlainText("Family code", code.code))
                            }) { Icon(Icons.Default.ContentCopy, "Copy") }
                            IconButton(onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Join my " + AppPrefs.familyName(context) + " family in Family Connect. Code: " + code.code + " (valid 10 minutes, one use).")
                                }
                                context.startActivity(Intent.createChooser(share, "Share family code"))
                            }) { Icon(Icons.Default.Share, "Share") }
                        }
                    }
                }
            }
        }

        item { V7Section("People", members.size.toString() + " joined") { onRefresh() } }
        items(members, key = { it.id }) { member ->
            V7Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).background(avatarColor(member.id), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(member.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(member.name + if (member.id == myId) "  • You" else "", fontWeight = FontWeight.Black, fontSize = 13.5.sp)
                        Text(
                            if (!member.locationVisible && member.id != myId) "Location hidden from you"
                            else member.motion + " • " + ageText(member.updatedAt),
                            color = V7Muted,
                            fontSize = 9.4.sp
                        )
                    }
                    if (AppPrefs.isOwner(context) && member.id != myId) {
                        IconButton(onClick = { removeTarget = member }) {
                            Icon(Icons.Default.PersonRemove, "Remove member", tint = V7Red)
                        }
                    }
                }
            }
        }

        item {
            V7Section("Places", "Add place") { showAddPlace = true }
        }

        if (places.isEmpty()) {
            item {
                V7Card {
                    Text("No saved places yet", fontWeight = FontWeight.Black)
                    Text("Add Home, Office, School or any place to get approach, arrival and leave alerts.", color = V7Muted, fontSize = 10.sp)
                }
            }
        } else {
            items(places, key = { it.id }) { place ->
                val target = members.firstOrNull { it.id == place.watchMemberId }?.name ?: "Everyone"
                V7Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFF5F3FF)) {
                            Icon(
                                if (place.name.contains("home", true)) Icons.Default.Home
                                else if (place.name.contains("office", true)) Icons.Default.Business
                                else Icons.Default.Place,
                                null,
                                tint = Color(0xFF7C3AED),
                                modifier = Modifier.padding(9.dp)
                            )
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(place.name, fontWeight = FontWeight.Black, fontSize = 12.5.sp)
                            Text("For " + target + " • radius " + place.radiusM.toInt() + " m", color = V7Muted, fontSize = 9.4.sp)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { FamilyCloud.deletePlace(context, place.id) }
                                result.onSuccess {
                                    AppPrefs.replacePlaces(context, it.places)
                                    onRefresh()
                                }.onFailure { error = it.message }
                            }
                        }) { Icon(Icons.Default.DeleteOutline, "Delete", tint = V7Red) }
                    }
                }
            }
        }

        error?.let {
            item {
                Surface(color = V7RedSoft, shape = RoundedCornerShape(14.dp)) {
                    Text(it, Modifier.padding(11.dp), color = V7Ink, fontSize = 10.5.sp)
                }
            }
        }
    }

    if (showAddPlace) {
        AddPlaceDialogV7(
            snapshot = snapshot,
            members = members,
            onDismiss = { showAddPlace = false },
            onSave = { place ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { FamilyCloud.upsertPlace(context, place) }
                    result.onSuccess {
                        AppPrefs.replacePlaces(context, it.places)
                        showAddPlace = false
                        onRefresh()
                    }.onFailure { error = it.message }
                }
            }
        )
    }

    removeTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove " + member.name + "?", fontWeight = FontWeight.Black) },
            text = { Text("They will immediately lose access to new family location updates and shared information.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { FamilyCloud.removeMember(context, member.id) }
                            result.onSuccess {
                                removeTarget = null
                                onRefresh()
                            }.onFailure { error = it.message }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = V7Red)
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AddPlaceDialogV7(
    snapshot: DeviceSnapshot,
    members: List<CloudMember>,
    onDismiss: () -> Unit,
    onSave: (SavedPlace) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf(snapshot.latitude?.toString() ?: "") }
    var lon by rememberSaveable { mutableStateOf(snapshot.longitude?.toString() ?: "") }
    var radius by rememberSaveable { mutableFloatStateOf(180f) }
    var target by rememberSaveable { mutableStateOf(members.firstOrNull()?.id) }
    var menu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add smart place", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Home", "Office", "School", "Parents", "Gym").forEach {
                        AssistChip(onClick = { name = it }, label = { Text(it) })
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Place name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                if (snapshot.latitude != null && snapshot.longitude != null) {
                    TextButton(onClick = {
                        lat = snapshot.latitude.toString()
                        lon = snapshot.longitude.toString()
                    }) {
                        Icon(Icons.Default.MyLocation, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Use my current location")
                    }
                }
                Text("Arrival radius: " + radius.toInt() + " m", fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                Slider(radius, { radius = it }, valueRange = 100f..500f, steps = 7)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(members.firstOrNull { it.id == target }?.name ?: "Select member", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        members.forEach { member ->
                            DropdownMenuItem(
                                text = { Text(member.name) },
                                onClick = { target = member.id; menu = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.trim().length >= 2 && lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null && target != null,
                onClick = {
                    onSave(
                        SavedPlace(
                            UUID.randomUUID().toString(),
                            name.trim(),
                            lat.toDouble(),
                            lon.toDouble(),
                            radius,
                            target
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SafetyCentre(
    snapshot: DeviceSnapshot,
    onStartTracking: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sosActive by rememberSaveable { mutableStateOf(false) }
    var checkinDialog by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(V7Canvas),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { V7TopBar("Safety", "Emergency tools that stay visible and explicit", Icons.Default.HealthAndSafety) }

        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.background(
                        Brush.linearGradient(
                            if (sosActive) listOf(Color(0xFF9F1239), Color(0xFFE11D48))
                            else listOf(Color(0xFF4C0519), Color(0xFFBE123C), Color(0xFFE11D48))
                        ),
                        RoundedCornerShape(28.dp)
                    ).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("EMERGENCY SOS", color = Color.White.copy(alpha = .75f), fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(11.dp))
                    Box(
                        Modifier.size(118.dp)
                            .background(Color.White.copy(alpha = .13f), CircleShape)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    sosActive = !sosActive
                                    if (sosActive) {
                                        onStartTracking()
                                        EmergencyAudio.start(context)
                                        scope.launch(Dispatchers.IO) {
                                            FamilyCloud.publishEvent(
                                                context,
                                                "SOS",
                                                "SOS from " + AppPrefs.profileName(context),
                                                "Emergency assistance requested. Open Family Connect for live location."
                                            )
                                        }
                                        lastAction = "SOS sent to family"
                                    } else {
                                        EmergencyAudio.stop()
                                        scope.launch(Dispatchers.IO) {
                                            FamilyCloud.publishEvent(
                                                context,
                                                "SOS_END",
                                                "SOS ended by " + AppPrefs.profileName(context),
                                                "The emergency SOS session was ended."
                                            )
                                        }
                                        lastAction = "SOS ended"
                                    }
                                    onRefresh()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (sosActive) Icons.Default.StopCircle else Icons.Default.Sos, null, tint = Color.White, modifier = Modifier.size(40.dp))
                            Text(if (sosActive) "END" else "HOLD", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(11.dp))
                    Text(
                        if (sosActive) "SOS active • alarm sounding" else "Long-press to start SOS",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                    Text(
                        if (sosActive) "Live location + battery " + snapshot.battery + "% are being shared."
                        else "Sends an urgent family alert and starts live location.",
                        color = Color.White.copy(alpha = .78f),
                        fontSize = 10.5.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        item { V7Section("Emergency actions") }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(
                    Modifier.weight(1f),
                    Icons.Default.HowToReg,
                    "Check in",
                    "Send status + optional message",
                    V7Green,
                    V7GreenSoft
                ) { checkinDialog = true }
                QuickAction(
                    Modifier.weight(1f),
                    Icons.Default.MyLocation,
                    "Share live",
                    "Start location sharing now",
                    V7Blue,
                    V7BlueSoft
                ) {
                    onStartTracking()
                    lastAction = "Live location sharing started"
                }
            }
        }

        item {
            V7Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Color(0xFFF5F3FF)) {
                        Icon(Icons.Default.PrivacyTip, null, tint = Color(0xFF7C3AED), modifier = Modifier.padding(9.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("Visible safety only", fontWeight = FontWeight.Black, fontSize = 12.5.sp)
                        Text("No hidden microphone, camera or message reading is used.", color = V7Muted, fontSize = 9.5.sp)
                    }
                }
            }
        }

        lastAction?.let {
            item {
                Surface(color = V7GreenSoft, shape = RoundedCornerShape(14.dp)) {
                    Text(it, Modifier.padding(11.dp), color = Color(0xFF065F46), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (checkinDialog) {
        CheckInDialog(
            onDismiss = { checkinDialog = false },
            onSend = { status, message ->
                checkinDialog = false
                scope.launch(Dispatchers.IO) {
                    FamilyCloud.publishEvent(
                        context,
                        if (status == "Need Help") "CHECKIN_HELP" else "CHECKIN_OK",
                        AppPrefs.profileName(context) + " checked in: " + status,
                        if (message.isBlank()) status else status + " • " + message.trim()
                    )
                }
                lastAction = "Check-in sent"
                onRefresh()
            }
        )
    }
}

@Composable
private fun CheckInDialog(
    onDismiss: () -> Unit,
    onSend: (String, String) -> Unit
) {
    var status by rememberSaveable { mutableStateOf("I'm Fine") }
    var message by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send check-in", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("I'm Fine", "Call Me", "Need Help").forEach { option ->
                        FilterChip(
                            selected = status == option,
                            onClick = { status = option },
                            label = { Text(option, fontSize = 9.5.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(240) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Optional message") },
                    placeholder = { Text("e.g. Reached safely, call me in 10 min") },
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = { Button(onClick = { onSend(status, message) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PrivacyProfile(
    snapshot: DeviceSnapshot,
    trackingEnabled: Boolean,
    onToggleTracking: () -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var rules by remember { mutableStateOf<List<SharingRule>>(emptyList()) }
    var rulesBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var shareSpeed by rememberSaveable { mutableStateOf(prefs.getBoolean("share_speed", true)) }
    var shareBattery by rememberSaveable { mutableStateOf(prefs.getBoolean("share_battery", true)) }
    var smartStops by rememberSaveable { mutableStateOf(prefs.getBoolean("unsaved_stop_alerts", false)) }
    var stopMinutes by rememberSaveable { mutableIntStateOf(prefs.getInt("unsaved_stop_minutes", 10)) }

    suspend fun reloadRules() {
        rulesBusy = true
        val result = withContext(Dispatchers.IO) { FamilyCloud.getSharing(context) }
        rulesBusy = false
        result.onSuccess { rules = it; error = null }.onFailure { error = it.message }
    }

    fun updateRule(rule: SharingRule, location: Boolean = rule.locationEnabled, speed: Boolean = rule.speedEnabled, battery: Boolean = rule.batteryEnabled) {
        val old = rules
        rules = rules.map {
            if (it.viewerMemberId == rule.viewerMemberId) it.copy(
                locationEnabled = location,
                speedEnabled = speed,
                batteryEnabled = battery
            ) else it
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                FamilyCloud.setSharing(context, rule.viewerMemberId, location, speed, battery)
            }
            result.onFailure {
                rules = old
                error = it.message
            }
        }
    }

    LaunchedEffect(Unit) { reloadRules() }

    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = if (Build.VERSION.SDK_INT >= 29) ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED else true
    val notifications = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(V7Canvas),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { V7TopBar("You", "Privacy, automation and device controls", Icons.Default.AccountCircle) }

        item {
            V7Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = if (trackingEnabled) V7GreenSoft else V7RedSoft) {
                        Icon(
                            if (trackingEnabled) Icons.Default.LocationOn else Icons.Default.LocationOff,
                            null,
                            tint = if (trackingEnabled) V7Green else V7Red,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (trackingEnabled) "Live sharing is on" else "Live sharing is paused", fontWeight = FontWeight.Black, fontSize = 14.sp)
                        Text("Master control for this phone", color = V7Muted, fontSize = 9.5.sp)
                    }
                    Switch(checked = trackingEnabled, onCheckedChange = { onToggleTracking() })
                }
            }
        }

        item { V7Section("Who can see me", "Per person") { scope.launch { reloadRules() } } }
        if (rulesBusy && rules.isEmpty()) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        } else if (rules.isEmpty()) {
            item {
                V7Card {
                    Text("No other family member yet", fontWeight = FontWeight.Bold)
                    Text("Person-specific controls appear after someone joins.", color = V7Muted, fontSize = 10.sp)
                }
            }
        } else {
            items(rules, key = { it.viewerMemberId }) { rule ->
                V7Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(avatarColor(rule.viewerMemberId), CircleShape), contentAlignment = Alignment.Center) {
                            Text(rule.viewerName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rule.viewerName, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            Text(if (rule.locationEnabled) "Can see your live location" else "Your location is hidden", color = if (rule.locationEnabled) V7Muted else V7Red, fontSize = 9.3.sp)
                        }
                        Switch(checked = rule.locationEnabled, onCheckedChange = { updateRule(rule, location = it) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp)).padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Speed", fontSize = 9.8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = rule.speedEnabled, enabled = rule.locationEnabled && shareSpeed, onCheckedChange = { updateRule(rule, speed = it) }, modifier = Modifier.scale(.78f))
                        Text("Battery", fontSize = 9.8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = rule.batteryEnabled, enabled = shareBattery, onCheckedChange = { updateRule(rule, battery = it) }, modifier = Modifier.scale(.78f))
                    }
                }
            }
        }

        item { V7Section("Smart automation") }
        item {
            V7Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(13.dp), color = V7BlueSoft) {
                        Icon(Icons.Default.Timer, null, tint = V7Blue, modifier = Modifier.padding(9.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Unscheduled stop alerts", fontWeight = FontWeight.Black, fontSize = 12.5.sp)
                        Text("If you stop outside saved places, identify the place and notify family.", color = V7Muted, fontSize = 9.3.sp)
                    }
                    Switch(
                        checked = smartStops,
                        onCheckedChange = {
                            smartStops = it
                            prefs.edit().putBoolean("unsaved_stop_alerts", it).apply()
                        }
                    )
                }
                if (smartStops) {
                    Spacer(Modifier.height(10.dp))
                    Text("Notify after", color = V7Muted, fontSize = 9.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(5, 10, 15, 20).forEach { mins ->
                            FilterChip(
                                selected = stopMinutes == mins,
                                onClick = {
                                    stopMinutes = mins
                                    prefs.edit().putInt("unsaved_stop_minutes", mins).apply()
                                },
                                label = { Text(mins.toString() + " min", fontSize = 9.sp) }
                            )
                        }
                    }
                }
            }
        }

        item { V7Section("What this phone shares") }
        item {
            V7Card {
                ToggleRow(Icons.Default.Speed, "Driving speed", "Current, average and maximum", shareSpeed) {
                    shareSpeed = it
                    prefs.edit().putBoolean("share_speed", it).apply()
                }
                HorizontalDivider()
                ToggleRow(Icons.Default.BatteryChargingFull, "Battery", "Battery percentage during travel", shareBattery) {
                    shareBattery = it
                    prefs.edit().putBoolean("share_battery", it).apply()
                }
            }
        }

        item { V7Section("Permission health") }
        item {
            V7Card {
                PermissionRow("Precise location", fine)
                PermissionRow("Background location", background)
                PermissionRow("Notifications", notifications)
                Spacer(Modifier.height(9.dp))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + context.packageName)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Open Android permissions") }
            }
        }

        item {
            V7Card {
                Text("Account & data", fontWeight = FontWeight.Black, fontSize = 13.sp)
                Text(AppPrefs.familyName(context) + " • " + if (AppPrefs.isOwner(context)) "Owner" else "Member", color = V7Muted, fontSize = 9.5.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = V7Red),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Logout, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Leave this family on this phone")
                }
            }
        }

        error?.let {
            item {
                Surface(color = V7RedSoft, shape = RoundedCornerShape(14.dp)) {
                    Text(it, Modifier.padding(11.dp), fontSize = 10.5.sp, color = V7Ink)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = V7BlueSoft) {
            Icon(icon, null, tint = V7Blue, modifier = Modifier.padding(8.dp).size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
            Text(subtitle, color = V7Muted, fontSize = 8.8.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(title: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = if (ok) V7Green else Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 10.8.sp, modifier = Modifier.weight(1f))
        Text(if (ok) "Ready" else "Needs attention", color = V7Muted, fontSize = 9.sp)
    }
}
