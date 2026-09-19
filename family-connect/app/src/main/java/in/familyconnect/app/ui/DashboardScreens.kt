package com.familyconnect.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.familyconnect.app.ExternalDestinationShare
import com.familyconnect.app.cloud.CloudMember
import com.familyconnect.app.cloud.CloudState
import com.familyconnect.app.cloud.FamilyCloud
import com.familyconnect.app.cloud.SharingRule
import com.familyconnect.app.cloud.ShortInvite
import com.familyconnect.app.model.DeviceSnapshot
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
    var tripDialog by remember { mutableStateOf(false) }
    var tripRefresh by remember { mutableIntStateOf(0) }
    var routingBusy by remember { mutableStateOf(false) }
    var tripError by remember { mutableStateOf<String?>(null) }
    val sharedMapText by ExternalDestinationShare.text.collectAsState()
    var resolvedSharedPlace by remember { mutableStateOf<SavedPlace?>(null) }
    var resolvingSharedPlace by remember { mutableStateOf(false) }
    val trip = remember(snapshot, tripRefresh) { AppPrefs.trip(context) }
    val places = cloud?.places ?: AppPrefs.places(context)
    val myId = AppPrefs.memberId(context)

    val launchTrip: (SavedPlace) -> Unit = { place ->
        tripError = null
        val lat = snapshot.latitude
        val lon = snapshot.longitude
        if (lat == null || lon == null) {
            if (!trackingEnabled) onToggleTracking()
            tripError = "Waiting for a fresh GPS fix. Location sharing has been requested; try again in a few seconds."
        } else {
            routingBusy = true
            if (!trackingEnabled) onToggleTracking()
            scope.launch {
                val routeResult = withContext(Dispatchers.IO) {
                    FamilyCloud.roadRoute(context, lat, lon, place.lat, place.lon)
                }
                routeResult.onSuccess { route ->
                    val routeSync = withContext(Dispatchers.IO) {
                        FamilyCloud.setTripRoute(context, route)
                    }
                    if (routeSync.isSuccess) {
                        AppPrefs.startTrip(context, place, route)
                        ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
                        withContext(Dispatchers.IO) {
                            FamilyCloud.publishEvent(
                                context,
                                "TRIP_STARTED",
                                AppPrefs.profileName(context) + " started a trip",
                                "Going to " + place.name + " • " +
                                    (if (route.distanceM < 1000f) route.distanceM.toInt().toString() + " m by road"
                                    else "%.1f km by road".format(route.distanceM / 1000f)) +
                                    " • ETA " + ((route.durationS + 59) / 60).coerceAtLeast(1) + " min"
                            )
                        }
                        tripRefresh++
                        onRefresh()
                    } else {
                        tripError = routeSync.exceptionOrNull()?.message ?: "Route could not be shared with the family."
                    }
                }.onFailure {
                    tripError = it.message ?: "Road route could not be calculated. Please try again."
                }
                routingBusy = false
            }
        }
    }

    LaunchedEffect(sharedMapText) {
        val text = sharedMapText ?: return@LaunchedEffect
        resolvingSharedPlace = true
        val result = withContext(Dispatchers.IO) { FamilyCloud.resolveMapShare(context, text) }
        resolvingSharedPlace = false
        result.onSuccess { d ->
            resolvedSharedPlace = SavedPlace(
                id = "shared-" + System.currentTimeMillis(),
                name = d.name,
                lat = d.lat,
                lon = d.lon
            )
        }.onFailure {
            tripError = it.message ?: "Could not read the Google Maps location."
            ExternalDestinationShare.consume()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppHeader(
                AppPrefs.familyName(context),
                "Signed in as " + AppPrefs.profileName(context)
            )
        }

        cloudError?.let { message ->
            item {
                Surface(color = AmberSoft, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, null, tint = Amber)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Family sync interrupted", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            Text(message, color = Muted, fontSize = 10.5.sp, maxLines = 2)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    }
                }
            }
        }

        tripError?.let { message ->
            item {
                Surface(color = RoseSoft, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Route, null, tint = Rose)
                        Spacer(Modifier.width(9.dp))
                        Text(message, color = Color(0xFF8E2B3D), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { tripError = null }) { Icon(Icons.Default.Close, "Dismiss") }
                    }
                }
            }
        }

        if (routingBusy) {
            item {
                Surface(color = PurpleSoft, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Calculating road route", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Getting drivable path, road distance and ETA…", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(27.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.background(
                        Brush.linearGradient(listOf(Color(0xFF173E70), Color(0xFF2F6FED), Color(0xFF2BB3C0))),
                        RoundedCornerShape(27.dp)
                    ).padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FAMILY NOW", color = Color(0xFFD6F3F6), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                if (trackingEnabled) "Live sharing is active" else "Location sharing is paused",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 22.sp
                            )
                            Text(
                                if (trackingEnabled) "Family map and travel status update from this phone." else "Turn it on when you want your joined family to see your location.",
                                color = Color(0xFFDAD9EC),
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Box(
                            Modifier.size(58.dp).background(Color.White.copy(alpha = .11f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (trackingEnabled) Icons.Default.GpsFixed else Icons.Default.LocationOff, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(
                            onClick = onToggleTracking,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF292653)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (trackingEnabled) "Pause sharing" else "Start sharing", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        FilledTonalButton(
                            onClick = onOpenMap,
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha=.13f), contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Map, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Live map", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (trip.active) {
            item {
                ActiveTripCard(
                    destination = trip.destinationName ?: "Destination",
                    remainingM = trip.remainingM,
                    current = snapshot.speedKmh,
                    average = trip.averageSpeed,
                    max = trip.maxSpeed,
                    eta = trip.etaMinutes,
                    onEnd = {
                        val destination = AppPrefs.trip(context).destinationName ?: "destination"
                        AppPrefs.stopTrip(context)
                        scope.launch(Dispatchers.IO) {
                            FamilyCloud.clearTripRoute(context)
                            FamilyCloud.publishEvent(
                                context,
                                "TRIP_ENDED",
                                AppPrefs.profileName(context) + " ended the trip",
                                "Trip to " + destination + " was ended."
                            )
                        }
                        ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
                        tripRefresh++
                        onRefresh()
                    }
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PremiumAction(
                        Modifier.weight(1f),
                        Icons.Default.Route,
                        "Start trip",
                        if (places.isEmpty()) "Add a destination first" else "Destination + ETA",
                        PurpleSoft,
                        Purple
                    ) { tripDialog = true }
                    PremiumAction(
                        Modifier.weight(1f),
                        Icons.Default.AddLocationAlt,
                        "Places",
                        "Home, office & more",
                        MintSoft,
                        Mint,
                        onOpenFamily
                    )
                }
            }
        }

        item {
            SectionTitle(
                "Family status",
                when {
                    cloudBusy && cloud == null -> "Syncing…"
                    cloud == null -> "No cloud data"
                    else -> cloud.members.size.toString() + " joined"
                }
            )
        }

        val members = cloud?.members.orEmpty()
        if (members.isEmpty()) {
            item {
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Default.PersonAdd, PurpleSoft, Purple)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("No other family member yet", fontWeight = FontWeight.ExtraBold)
                            Text("Share your invitation code; joined members will appear automatically.", color = Muted, fontSize = 10.5.sp)
                        }
                        TextButton(onClick = onOpenFamily) { Text("Invite") }
                    }
                }
            }
        } else {
            items(members.sortedBy { if (it.id == myId) 0 else 1 }, key = { it.id }) { member ->
                CloudMemberCard(member, isMe = member.id == myId, onOpenMap = onOpenMap)
            }
        }

        val events = cloud?.events.orEmpty().sortedByDescending { it.createdAt }.take(4)
        if (events.isNotEmpty()) {
            item { SectionTitle("Recent family updates") }
            items(events, key = { it.id }) { event ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.45f))
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(
                            when (event.type) {
                                "OVERSPEED" -> Icons.Default.Speed
                                "PLACE_ENTER", "TRIP_ARRIVED" -> Icons.Default.CheckCircle
                                "PLACE_APPROACH", "TRIP_APPROACHING" -> Icons.Default.NearMe
                                else -> Icons.Default.NotificationsActive
                            },
                            if (event.type == "OVERSPEED") RoseSoft else PurpleSoft,
                            if (event.type == "OVERSPEED") Rose else Purple,
                            38
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(event.title, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            Text(event.memberName + " • " + event.body, color = Muted, fontSize = 10.5.sp, maxLines = 2)
                        }
                        Text(ageText(event.createdAt), color = Muted, fontSize = 9.sp)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
    }

    if (tripDialog) {
        TripStartDialog(
            places = places,
            onDismiss = { tripDialog = false },
            onStart = { place ->
                tripDialog = false
                launchTrip(place)
            },
            onNeedPlace = {
                tripDialog = false
                onOpenFamily()
            },
            onOpenGoogleMaps = {
                tripDialog = false
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).apply {
                    setPackage("com.google.android.apps.maps")
                }
                runCatching { context.startActivity(intent) }.onFailure {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")))
                }
            }
        )
    }

    if (resolvingSharedPlace) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Reading Google Maps place", fontWeight = FontWeight.Black) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Getting the selected location…", color = Muted)
                }
            },
            confirmButton = {}
        )
    }

    resolvedSharedPlace?.let { place ->
        AlertDialog(
            onDismissRequest = {
                resolvedSharedPlace = null
                ExternalDestinationShare.consume()
            },
            icon = { Icon(Icons.Default.Map, null, tint = Purple) },
            title = { Text("Start trip to this place?", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Text(place.name, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Selected from Google Maps sharing.", color = Muted, fontSize = 11.sp)
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
}

@Composable
private fun ActiveTripCard(
    destination: String,
    remainingM: Float?,
    current: Int,
    average: Int,
    max: Int,
    eta: Int?,
    onEnd: () -> Unit
) {
    PremiumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Default.Navigation, PurpleSoft, Purple, 46)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Travelling to", color = Muted, fontSize = 10.sp)
                Text(destination, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(
                    (remainingM?.let { if (it < 1000f) it.toInt().toString() + " m by road" else "%.1f km by road".format(it / 1000f) } ?: "Calculating road distance") +
                        (eta?.let { " • Road ETA " + it + " min" } ?: ""),
                    color = Muted,
                    fontSize = 10.5.sp
                )
            }
            StatusPill("LIVE", MintSoft, Mint)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TripMetric(Modifier.weight(1f), "Current", current.toString(), "km/h")
            TripMetric(Modifier.weight(1f), "Average", average.toString(), "km/h")
            TripMetric(Modifier.weight(1f), "Maximum", max.toString(), "km/h")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.StopCircle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("End trip")
        }
    }
}

@Composable
private fun TripMetric(modifier: Modifier, label: String, value: String, unit: String) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(unit, color = Purple, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            Text(label, color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun PremiumAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    bg: Color,
    fg: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.48f))
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, bg, fg, 39)
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Text(subtitle, color = Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CloudMemberCard(member: CloudMember, isMe: Boolean, onOpenMap: () -> Unit) {
    val stale = System.currentTimeMillis() - member.updatedAt > 120_000L
    PremiumCard(onClick = onOpenMap) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(avatarColor(member.id), CircleShape), contentAlignment = Alignment.Center) {
                Text(member.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(13.dp)
                        .background(if (stale) Color(0xFFA7A8B5) else Mint, CircleShape)
                        .padding(1.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(member.name, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    if (isMe) {
                        Spacer(Modifier.width(7.dp))
                        StatusPill("You", PurpleSoft, Purple)
                    }
                }
                Text(
                    if (!member.locationVisible) "Location sharing paused for you"
                    else member.motion + " • " + ageText(member.updatedAt),
                    color = if (!member.locationVisible) Rose else Muted,
                    fontSize = 10.5.sp
                )
            }
            if (!member.locationVisible) StatusPill("Private", RoseSoft, Rose)
            else if (member.speedVisible && member.speed > 0) StatusPill(member.speed.toString() + " km/h", PurpleSoft, Purple)
        }
        Spacer(Modifier.height(11.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallMetric("Battery", if (member.batteryVisible && member.battery >= 0) member.battery.toString() + "%" else "Private")
            SmallMetric("Avg speed", if (member.speedVisible) member.avgSpeed.toString() + " km/h" else "Private")
            SmallMetric("Max speed", if (member.speedVisible) member.maxSpeed.toString() + " km/h" else "Private")
            SmallMetric("Updated", ageText(member.updatedAt))
        }
        member.destinationName?.let { destination ->
            Spacer(Modifier.height(10.dp))
            Surface(color = PurpleSoft.copy(alpha=.72f), shape = RoundedCornerShape(13.dp)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Route, null, tint = Purple, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Going to " + destination, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = Ink)
                        Text(
                            (member.remainingM?.let { if (it < 1000f) it.toInt().toString() + " m by road" else "%.1f km by road".format(it / 1000f) } ?: "Road distance updating") +
                                (member.etaMinutes?.let { " • ETA " + it + " min" } ?: ""),
                            color = Ink.copy(alpha=.66f),
                            fontSize = 9.8.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallMetric(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
        Text(label, color = Muted, fontSize = 8.5.sp)
    }
}

@Composable
private fun TripStartDialog(
    places: List<SavedPlace>,
    onDismiss: () -> Unit,
    onStart: (SavedPlace) -> Unit,
    onNeedPlace: () -> Unit,
    onOpenGoogleMaps: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where are you going?", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenGoogleMaps),
                    color = PurpleSoft,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Map, null, tint = Purple)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Choose in Google Maps", fontWeight = FontWeight.Black, color = Ink)
                            Text(
                                "Pick any place in Google Maps → Share → Family Connect",
                                color = Muted,
                                fontSize = 9.8.sp
                            )
                        }
                        Icon(Icons.Default.OpenInNew, null, tint = Purple)
                    }
                }

                if (places.isNotEmpty()) {
                    Text("Saved places", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    places.forEach { place ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onStart(place) },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Place, null, tint = Purple)
                                Spacer(Modifier.width(9.dp))
                                Text(place.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                } else {
                    Text(
                        "No saved places yet. You can still choose any destination through Google Maps.",
                        color = Muted,
                        fontSize = 10.5.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        dismissButton = {
            TextButton(onClick = onNeedPlace) { Text("Manage saved places") }
        }
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
    val scope = rememberCoroutineScope()

    Configuration.getInstance().userAgentValue = context.packageName

    val members = cloud?.members.orEmpty()
    val locatedMembers = members.filter { it.lat != null && it.lon != null }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var followSelected by remember { mutableStateOf(true) }
    var mapError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(locatedMembers.map { it.id }) {
        if (selectedId == null || locatedMembers.none { it.id == selectedId }) {
            selectedId = locatedMembers.firstOrNull()?.id
        }
    }

    val selected = members.firstOrNull { it.id == selectedId } ?: locatedMembers.firstOrNull()

    val routeCache = remember { mutableStateMapOf<String, RoadRoute>() }
    val routeVersionCache = remember { mutableStateMapOf<String, Long>() }

    LaunchedEffect(
        members.map { Triple(it.id, it.tripActive, it.routeUpdatedAt) }
    ) {
        val activeIds = members.filter { it.tripActive }.map { it.id }.toSet()
        routeCache.keys.toList().filterNot { it in activeIds }.forEach {
            routeCache.remove(it)
            routeVersionCache.remove(it)
        }

        members.filter { it.tripActive && it.routeUpdatedAt > 0L }.forEach { member ->
            val loadedVersion = routeVersionCache[member.id]
            if (loadedVersion != member.routeUpdatedAt) {
                val result = withContext(Dispatchers.IO) { FamilyCloud.getTripRoute(context, member.id) }
                result.onSuccess { route ->
                    if (route.points.size >= 2) {
                        routeCache[member.id] = route
                        routeVersionCache[member.id] = member.routeUpdatedAt
                    }
                }
            }
        }
    }

    val initialCenter = selected?.let { member ->
        if (member.lat != null && member.lon != null) GeoPoint(member.lat, member.lon) else null
    } ?: if (snapshot.latitude != null && snapshot.longitude != null) {
        GeoPoint(snapshot.latitude, snapshot.longitude)
    } else {
        GeoPoint(26.8467, 80.9462)
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setUseDataConnection(true)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(if (locatedMembers.isEmpty()) 12.5 else 16.4)
            controller.setCenter(initialCenter)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                followSelected = false
            }
            false
        }
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

    fun fitSelectedRoute() {
        val member = members.firstOrNull { it.id == selectedId } ?: return
        val route = routeCache[member.id]
        val points = mutableListOf<GeoPoint>()
        member.lat?.let { lat -> member.lon?.let { lon -> points += GeoPoint(lat, lon) } }
        route?.points?.forEach { points += GeoPoint(it.lat, it.lon) }
        member.destinationLat?.let { lat -> member.destinationLon?.let { lon -> points += GeoPoint(lat, lon) } }

        if (points.size >= 2) {
            val north = points.maxOf { it.latitude }
            val south = points.minOf { it.latitude }
            val east = points.maxOf { it.longitude }
            val west = points.minOf { it.longitude }
            mapView.post {
                runCatching {
                    mapView.zoomToBoundingBox(
                        org.osmdroid.util.BoundingBox(north, east, south, west),
                        true,
                        90
                    )
                }
            }
        } else if (member.lat != null && member.lon != null) {
            mapView.controller.setZoom(16.4)
            mapView.controller.animateTo(GeoPoint(member.lat, member.lon))
        }
    }

    LaunchedEffect(selectedId) {
        val member = members.firstOrNull { it.id == selectedId }
        if (member?.lat != null && member.lon != null) {
            if (mapView.zoomLevelDouble < 16.0) mapView.controller.setZoom(16.4)
            mapView.controller.animateTo(GeoPoint(member.lat, member.lon))
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { map ->
                runCatching {
                    map.overlays.clear()

                    // Route polylines come from a separate, cached request and are NOT part
                    // of the frequent live-state payload.
                    locatedMembers.forEach { member ->
                        val route = routeCache[member.id]
                        if (member.tripActive && route != null && route.points.size >= 2) {
                            map.overlays.add(
                                Polyline().apply {
                                    setPoints(route.points.map { GeoPoint(it.lat, it.lon) })
                                    outlinePaint.strokeWidth = if (member.id == selectedId) 8f else 5f
                                    outlinePaint.color = if (member.id == selectedId) {
                                        Purple.toArgb()
                                    } else {
                                        avatarColor(member.id).toArgb()
                                    }
                                    outlinePaint.alpha = if (member.id == selectedId) 235 else 125
                                    isGeodesic = false
                                }
                            )
                        }
                    }

                    // Trip destinations.
                    locatedMembers.forEach { member ->
                        if (
                            member.tripActive &&
                            member.destinationLat != null &&
                            member.destinationLon != null &&
                            member.destinationName != null
                        ) {
                            map.overlays.add(
                                Marker(map).apply {
                                    position = GeoPoint(member.destinationLat, member.destinationLon)
                                    icon = destinationMarkerDrawable(context, member)
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    title = member.destinationName
                                    snippet = member.name + " destination"
                                    setOnMarkerClickListener { _, _ ->
                                        selectedId = member.id
                                        followSelected = true
                                        true
                                    }
                                }
                            )
                        }
                    }

                    // Every member's current live position.
                    locatedMembers.forEach { member ->
                        val point = GeoPoint(member.lat!!, member.lon!!)
                        map.overlays.add(
                            Marker(map).apply {
                                position = point
                                icon = memberMarkerDrawable(context, member, member.id == selectedId)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = member.name
                                snippet = member.speed.toString() + " km/h • " + member.motion
                                setOnMarkerClickListener { _, _ ->
                                    selectedId = member.id
                                    followSelected = true
                                    if (map.zoomLevelDouble < 16.0) map.controller.setZoom(16.4)
                                    map.controller.animateTo(point)
                                    true
                                }
                            }
                        )
                    }

                    if (followSelected) {
                        val member = members.firstOrNull { it.id == selectedId }
                        if (member?.lat != null && member.lon != null) {
                            map.controller.animateTo(GeoPoint(member.lat, member.lon))
                        }
                    }

                    map.invalidate()
                    mapError = null
                }.onFailure {
                    mapError = "Map rendering recovered from an error: " + (it.message ?: "unknown")
                }
            }
        )

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(PurpleSoft, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Map, null, tint = Purple, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Family Live Map", fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Text(
                        when {
                            locatedMembers.isEmpty() -> "Waiting for a shared family location"
                            locatedMembers.any { it.tripActive } ->
                                locatedMembers.size.toString() + " live • trip progress updating"
                            else -> locatedMembers.size.toString() + " live family member" + if (locatedMembers.size == 1) "" else "s"
                        },
                        color = Muted,
                        fontSize = 9.5.sp
                    )
                }

                IconButton(onClick = {
                    followSelected = !followSelected
                    if (followSelected) {
                        selected?.let { m ->
                            if (m.lat != null && m.lon != null) {
                                mapView.controller.setZoom(16.4)
                                mapView.controller.animateTo(GeoPoint(m.lat, m.lon))
                            }
                        }
                    }
                }) {
                    Icon(
                        if (followSelected) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                        "Follow selected",
                        tint = if (followSelected) Purple else Muted
                    )
                }

                IconButton(onClick = { fitSelectedRoute() }) {
                    Icon(Icons.Default.CropFree, "Fit selected route", tint = Purple)
                }

                if (!trackingEnabled) {
                    IconButton(onClick = onStartTracking) {
                        Icon(Icons.Default.MyLocation, "Share my location", tint = Purple)
                    }
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    followSelected = false
                    mapView.controller.zoomIn()
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Ink
            ) {
                Icon(Icons.Default.Add, "Zoom in")
            }
            SmallFloatingActionButton(
                onClick = {
                    followSelected = false
                    mapView.controller.zoomOut()
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Ink
            ) {
                Icon(Icons.Default.Remove, "Zoom out")
            }
            SmallFloatingActionButton(
                onClick = {
                    followSelected = true
                    selected?.let { m ->
                        if (m.lat != null && m.lon != null) {
                            mapView.controller.setZoom(16.4)
                            mapView.controller.animateTo(GeoPoint(m.lat, m.lon))
                        }
                    }
                },
                containerColor = Purple,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.MyLocation, "Recenter")
            }
        }

        mapError?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp, start = 12.dp, end = 12.dp),
                color = RoseSoft,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(message, Modifier.padding(10.dp), color = Color(0xFF8E2B3D), fontSize = 9.5.sp)
            }
        }

        if (locatedMembers.isEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 6.dp
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LocationSearching, null, tint = Purple, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(7.dp))
                    Text("No live location yet", fontWeight = FontWeight.Black)
                    Text(
                        "On at least one family phone, turn on location sharing and allow precise location.",
                        textAlign = TextAlign.Center,
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (locatedMembers.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(locatedMembers, key = { it.id }) { member ->
                        val active = member.id == selected?.id
                        Surface(
                            modifier = Modifier.clickable {
                                selectedId = member.id
                                followSelected = true
                                if (mapView.zoomLevelDouble < 16.0) mapView.controller.setZoom(16.4)
                            },
                            color = if (active) Purple else MaterialTheme.colorScheme.surface.copy(alpha = .97f),
                            contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(13.dp),
                            shadowElevation = 4.dp
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(22.dp).background(
                                        if (active) Color.White.copy(alpha = .18f) else avatarColor(member.id),
                                        CircleShape
                                    ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        member.name.take(1).uppercase(),
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                }
                                Spacer(Modifier.width(5.dp))
                                Text(member.name, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                Spacer(Modifier.width(5.dp))
                                Text(member.speed.toString() + " km/h", fontSize = 9.sp)
                            }
                        }
                    }
                }
            }

            selected?.let { member ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 9.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .985f),
                    shape = RoundedCornerShape(21.dp),
                    shadowElevation = 10.dp
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(41.dp).background(avatarColor(member.id), CircleShape),
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
                                    color = Muted,
                                    fontSize = 9.4.sp
                                )
                            }
                            StatusPill(member.speed.toString() + " km/h", PurpleSoft, Purple)
                        }

                        Spacer(Modifier.height(9.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
                                .padding(9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SmallMetric("Current", member.speed.toString() + " km/h")
                            SmallMetric("Average", member.avgSpeed.toString() + " km/h")
                            SmallMetric("Maximum", member.maxSpeed.toString() + " km/h")
                            SmallMetric("Battery", if (member.battery >= 0) member.battery.toString() + "%" else "Private")
                        }

                        if (member.tripActive && member.destinationName != null) {
                            Spacer(Modifier.height(9.dp))
                            Row(
                                Modifier.fillMaxWidth().background(PurpleSoft, RoundedCornerShape(13.dp)).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Navigation, null, tint = Purple, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Going to " + member.destinationName,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.5.sp,
                                        color = Ink
                                    )
                                    val distanceText = member.remainingM?.let {
                                        if (it < 1000f) it.toInt().toString() + " m by road"
                                        else "%.1f km by road".format(it / 1000f)
                                    } ?: "Road distance updating"
                                    Text(
                                        distanceText +
                                            (member.etaMinutes?.let { " • ETA " + it + " min" } ?: "") +
                                            if (routeCache[member.id]?.points?.size ?: 0 >= 2) " • route loaded" else " • route loading",
                                        color = Ink.copy(alpha = .67f),
                                        fontSize = 9.6.sp
                                    )
                                }
                                TextButton(onClick = { fitSelectedRoute() }) {
                                    Text("FIT", fontSize = 9.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun destinationMarkerDrawable(context: Context, member: CloudMember): BitmapDrawable {
    val w = 220
    val h = 78
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF7FFFFFF.toInt() }
    val rect = RectF(4f, 4f, (w - 4).toFloat(), 64f)
    canvas.drawRoundRect(rect, 22f, 22f, bg)

    val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Purple.toArgb() }
    canvas.drawCircle(31f, 34f, 20f, circle)

    val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    canvas.drawLine(25f, 22f, 25f, 47f, flagPaint)
    val flagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val flag = android.graphics.Path().apply {
        moveTo(27f, 22f)
        lineTo(45f, 28f)
        lineTo(27f, 34f)
        close()
    }
    canvas.drawPath(flag, flagFill)

    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF171829.toInt()
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
    }
    val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Purple.toArgb()
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText((member.destinationName ?: "Destination").take(18), 60f, 29f, title)
    val info = (member.etaMinutes?.let { "ETA " + it + " min" } ?: "Destination") +
        (member.remainingM?.let { " • " + if (it < 1000f) it.toInt().toString() + " m" else "%.1f km".format(it / 1000f) } ?: "")
    canvas.drawText(info.take(24), 60f, 51f, sub)

    val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF7FFFFFF.toInt() }
    val path = android.graphics.Path().apply {
        moveTo(98f, 63f)
        lineTo(110f, 77f)
        lineTo(122f, 63f)
        close()
    }
    canvas.drawPath(path, pin)
    return BitmapDrawable(context.resources, bitmap)
}

private fun memberMarkerDrawable(
    context: Context,
    member: CloudMember,
    selected: Boolean
): BitmapDrawable {
    val w = 214
    val h = 78
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    canvas.drawRoundRect(RectF(6f, 8f, (w - 4).toFloat(), 65f), 25f, 25f, shadow)

    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFAFFFFFF.toInt() }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) Purple.toArgb() else 0x22000000
        style = Paint.Style.STROKE
        strokeWidth = if (selected) 4f else 2f
    }
    val rect = RectF(4f, 4f, (w - 6).toFloat(), 61f)
    canvas.drawRoundRect(rect, 24f, 24f, bg)
    canvas.drawRoundRect(rect, 24f, 24f, border)

    val avatar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = avatarColor(member.id).toArgb() }
    canvas.drawCircle(34f, 32f, 22f, avatar)

    val initial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(member.name.take(1).uppercase(), 34f, 39f, initial)

    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF122033.toInt()
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
    }
    val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (member.speed > 0) Purple.toArgb() else 0xFF68768A.toInt()
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText(member.name.take(13), 66f, 28f, namePaint)
    val info = if (member.locationVisible) {
        member.speed.toString() + " km/h • " + member.motion.take(10)
    } else {
        "Location private"
    }
    canvas.drawText(info.take(20), 66f, 48f, infoPaint)

    val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFAFFFFFF.toInt() }
    val path = android.graphics.Path().apply {
        moveTo(92f, 60f)
        lineTo(106f, 77f)
        lineTo(120f, 60f)
        close()
    }
    canvas.drawPath(path, pin)

    if (selected) {
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Purple.toArgb() }
        canvas.drawCircle(197f, 19f, 6f, dot)
    }

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
    var addPlace by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shortInvite by remember { mutableStateOf<ShortInvite?>(null) }
    var inviteBusy by remember { mutableStateOf(false) }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val members = cloud?.members.orEmpty()
    val places = cloud?.places ?: AppPrefs.places(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppHeader(
                AppPrefs.familyName(context),
                members.size.toString() + " joined member" + if (members.size == 1) "" else "s"
            )
        }

        item {
            PremiumCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Default.PersonAddAlt1, SkySoft, Sky)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Invite family member", fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text("Use a short one-time code — no long invitation string.", color = Muted, fontSize = 10.5.sp)
                    }
                }

                Spacer(Modifier.height(13.dp))

                if (shortInvite == null) {
                    Button(
                        enabled = !inviteBusy,
                        onClick = {
                            inviteBusy = true
                            error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { FamilyCloud.generateShortInvite(context) }
                                inviteBusy = false
                                result.onSuccess { shortInvite = it }
                                    .onFailure { error = it.message ?: "Could not generate invite code" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (inviteBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Pin, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Generate 6-digit code", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    val invite = shortInvite!!
                    Surface(
                        color = SkySoft,
                        shape = RoundedCornerShape(17.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(15.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("JOIN CODE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                invite.code.chunked(3).joinToString("  "),
                                color = Navy,
                                fontWeight = FontWeight.Black,
                                fontSize = 30.sp,
                                letterSpacing = 4.sp
                            )
                            Text("Valid for 10 minutes • one use", color = Muted, fontSize = 9.5.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Family Connect code", invite.code)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Button(
                            onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Join my " + AppPrefs.familyName(context) + " family in Family Connect.\nCode: " + invite.code +
                                            "\nThis code is valid for 10 minutes and can be used once."
                                    )
                                }
                                context.startActivity(Intent.createChooser(share, "Share family code"))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                    TextButton(
                        onClick = {
                            shortInvite = null
                            inviteBusy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { FamilyCloud.generateShortInvite(context) }
                                inviteBusy = false
                                result.onSuccess { shortInvite = it }
                                    .onFailure { error = it.message ?: "Could not refresh code" }
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Generate new code", fontSize = 10.5.sp)
                    }
                }
            }
        }

        error?.let {
            item {
                Surface(color = RoseSoft, shape = RoundedCornerShape(14.dp)) {
                    Text(it, Modifier.padding(12.dp), color = Color(0xFF972A3C), fontSize = 10.5.sp)
                }
            }
        }

        item { SectionTitle("Joined family", members.size.toString() + " members") }
        if (members.isEmpty()) {
            item { Text("Syncing joined members…", color = Muted, fontSize = 11.sp) }
        } else {
            items(members, key = { it.id }) { member ->
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(avatarColor(member.id), CircleShape), contentAlignment = Alignment.Center) {
                            Text(member.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(member.name, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            Text(member.motion + " • " + ageText(member.updatedAt), color = Muted, fontSize = 10.sp)
                        }
                        if (member.speed > 0) StatusPill(member.speed.toString() + " km/h", PurpleSoft, Purple)
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Saved places", places.size.toString() + " rules")
            }
        }

        item {
            Button(
                onClick = { addPlace = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp)
            ) {
                Icon(Icons.Default.AddLocationAlt, null)
                Spacer(Modifier.width(7.dp))
                Text("Add Home, Office or another place", fontWeight = FontWeight.Bold)
            }
        }

        if (places.isEmpty()) {
            item {
                PremiumCard {
                    Text("No place rules yet", fontWeight = FontWeight.Bold)
                    Text(
                        "Create a place, choose which joined member it applies to, and their phone can publish approach, arrival and leave alerts.",
                        color = Muted,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        } else {
            items(places, key = { it.id }) { place ->
                val target = members.firstOrNull { it.id == place.watchMemberId }?.name ?: "Everyone"
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(
                            if (place.name.contains("home", true)) Icons.Default.Home
                            else if (place.name.contains("office", true)) Icons.Default.Business
                            else Icons.Default.Place,
                            MintSoft,
                            Mint
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(place.name, fontWeight = FontWeight.ExtraBold)
                            Text(
                                "For " + target + " • enter/leave • approach around 1 km • radius " + place.radiusM.toInt() + " m",
                                color = Muted,
                                fontSize = 9.8.sp,
                                maxLines = 2
                            )
                        }
                        IconButton(onClick = {
                            saving = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { FamilyCloud.deletePlace(context, place.id) }
                                saving = false
                                result.onSuccess {
                                    AppPrefs.replacePlaces(context, it.places)
                                    onRefresh()
                                }.onFailure { error = it.message }
                            }
                        }) {
                            Icon(Icons.Default.DeleteOutline, "Delete", tint = Rose)
                        }
                    }
                }
            }
        }

        item {
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Sync, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refresh family")
            }
        }
    }

    if (addPlace) {
        AddPlaceDialog(
            snapshot = snapshot,
            members = members,
            onDismiss = { addPlace = false },
            onSave = { place ->
                saving = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { FamilyCloud.upsertPlace(context, place) }
                    saving = false
                    result.onSuccess {
                        AppPrefs.replacePlaces(context, it.places)
                        addPlace = false
                        onRefresh()
                    }.onFailure {
                        error = it.message ?: "Could not save place"
                    }
                }
            }
        )
    }
}

@Composable
private fun AddPlaceDialog(
    snapshot: DeviceSnapshot,
    members: List<CloudMember>,
    onDismiss: () -> Unit,
    onSave: (SavedPlace) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf(snapshot.latitude?.toString() ?: "") }
    var lon by rememberSaveable { mutableStateOf(snapshot.longitude?.toString() ?: "") }
    var radius by rememberSaveable { mutableFloatStateOf(180f) }
    var selectedMemberId by rememberSaveable { mutableStateOf(members.firstOrNull()?.id) }
    var menu by remember { mutableStateOf(false) }

    val valid = name.trim().length >= 2 && lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add smart place", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("Home", "Office", "School", "Parents", "Gym").forEach { preset ->
                        AssistChip(onClick = { name = preset }, label = { Text(preset) })
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Place name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text("Latitude") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = lon,
                        onValueChange = { lon = it },
                        label = { Text("Longitude") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                if (snapshot.latitude != null && snapshot.longitude != null) {
                    TextButton(onClick = {
                        lat = snapshot.latitude.toString()
                        lon = snapshot.longitude.toString()
                    }) {
                        Icon(Icons.Default.MyLocation, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use my current location")
                    }
                }
                Text("Arrival radius: " + radius.toInt() + " m", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 100f..500f, steps = 7)

                Text("Apply this place rule to", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(members.firstOrNull { it.id == selectedMemberId }?.name ?: "Select member", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        members.forEach { member ->
                            DropdownMenuItem(
                                text = { Text(member.name) },
                                onClick = {
                                    selectedMemberId = member.id
                                    if (member.lat != null && member.lon != null) {
                                        lat = member.lat.toString()
                                        lon = member.lon.toString()
                                    }
                                    menu = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "The selected member's phone will detect approximately 1 km approach, arrival and leaving this radius while location sharing is active.",
                    color = Muted,
                    fontSize = 9.8.sp,
                    lineHeight = 13.sp
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid && selectedMemberId != null,
                onClick = {
                    onSave(
                        SavedPlace(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            lat = lat.toDouble(),
                            lon = lon.toDouble(),
                            radiusM = radius,
                            watchMemberId = selectedMemberId
                        )
                    )
                }
            ) { Text("Save place") }
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
    var sentText by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader("Safety Centre", "Visible, consent-based family safety") }
        item {
            PremiumCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.size(132.dp)
                            .background(if (sosActive) Color(0xFFC8324A) else Rose, CircleShape)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    sosActive = !sosActive
                                    if (sosActive) {
                                        onStartTracking()
                                        scope.launch(Dispatchers.IO) {
                                            FamilyCloud.publishEvent(
                                                context,
                                                "SOS",
                                                "SOS from " + AppPrefs.profileName(context),
                                                "Emergency assistance requested. Open Family Connect for live location."
                                            )
                                        }
                                        sentText = "SOS shared with the family cloud"
                                    } else {
                                        scope.launch(Dispatchers.IO) {
                                            FamilyCloud.publishEvent(
                                                context,
                                                "SOS_END",
                                                "SOS ended by " + AppPrefs.profileName(context),
                                                "The SOS session has been ended."
                                            )
                                        }
                                        sentText = "SOS ended"
                                    }
                                    onRefresh()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (sosActive) Icons.Default.StopCircle else Icons.Default.Sos, null, tint = Color.White, modifier = Modifier.size(42.dp))
                            Text(if (sosActive) "END SOS" else "SOS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (sosActive) "SOS live session active" else "Long-press for SOS", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(
                        if (sosActive) "Location sharing is active • battery " + snapshot.battery + "% • " + snapshot.network
                        else "Starts live location and publishes a high-priority family event.",
                        color = Muted,
                        fontSize = 10.5.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PremiumAction(
                    Modifier.weight(1f),
                    Icons.Default.HowToReg,
                    "I'm safe",
                    "Send family check-in",
                    MintSoft,
                    Mint
                ) {
                    scope.launch(Dispatchers.IO) {
                        FamilyCloud.publishEvent(
                            context,
                            "CHECKIN_OK",
                            AppPrefs.profileName(context) + " checked in",
                            "I'm fine."
                        )
                    }
                    sentText = "Check-in sent"
                    onRefresh()
                }
                PremiumAction(
                    Modifier.weight(1f),
                    Icons.Default.LiveHelp,
                    "Check on me",
                    "Ask family to follow",
                    PurpleSoft,
                    Purple
                ) {
                    onStartTracking()
                    scope.launch(Dispatchers.IO) {
                        FamilyCloud.publishEvent(
                            context,
                            "CHECKIN_REQUEST",
                            AppPrefs.profileName(context) + " started a safety watch",
                            "Live location sharing has been requested for this journey."
                        )
                    }
                    sentText = "Safety watch started"
                    onRefresh()
                }
            }
        }

        sentText?.let {
            item {
                Surface(color = MintSoft, shape = RoundedCornerShape(15.dp)) {
                    Text(it, Modifier.padding(12.dp), color = Color(0xFF176745), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }

        item {
            Surface(color = PurpleSoft.copy(alpha=.7f), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PrivacyTip, null, tint = Purple)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "No hidden camera, microphone or message reading is used. Android permissions remain authoritative.",
                        color = Ink.copy(alpha=.75f),
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
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

    var shareSpeed by rememberSaveable { mutableStateOf(prefs.getBoolean("share_speed", true)) }
    var shareBattery by rememberSaveable { mutableStateOf(prefs.getBoolean("share_battery", true)) }
    var speedLimit by rememberSaveable { mutableIntStateOf(prefs.getInt("speed_limit", 80)) }

    var sharingRules by remember { mutableStateOf<List<SharingRule>>(emptyList()) }
    var sharingBusy by remember { mutableStateOf(false) }
    var sharingError by remember { mutableStateOf<String?>(null) }

    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = if (Build.VERSION.SDK_INT >= 29) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else true
    val notification = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    suspend fun reloadSharing() {
        sharingBusy = true
        val result = withContext(Dispatchers.IO) { FamilyCloud.getSharing(context) }
        sharingBusy = false
        result.onSuccess {
            sharingRules = it
            sharingError = null
        }.onFailure {
            sharingError = it.message ?: "Could not load sharing controls"
        }
    }

    fun updateRule(
        rule: SharingRule,
        location: Boolean = rule.locationEnabled,
        speed: Boolean = rule.speedEnabled,
        battery: Boolean = rule.batteryEnabled
    ) {
        val old = sharingRules
        sharingRules = sharingRules.map {
            if (it.viewerMemberId == rule.viewerMemberId) {
                it.copy(
                    locationEnabled = location,
                    speedEnabled = speed,
                    batteryEnabled = battery
                )
            } else it
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                FamilyCloud.setSharing(
                    context,
                    rule.viewerMemberId,
                    location,
                    speed,
                    battery
                )
            }
            result.onFailure {
                sharingRules = old
                sharingError = it.message ?: "Could not change sharing"
            }
        }
    }

    LaunchedEffect(Unit) { reloadSharing() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppHeader(
                "Privacy & Controls",
                AppPrefs.profileName(context) + " • " + snapshot.network
            )
        }

        item {
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.background(
                        Brush.linearGradient(listOf(Color(0xFF173E70), Color(0xFF2F6FED), Color(0xFF2BB3C0))),
                        RoundedCornerShape(22.dp)
                    ).padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(46.dp).background(Color.White.copy(alpha = .14f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (trackingEnabled) Icons.Default.LocationOn else Icons.Default.LocationOff,
                            null,
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (trackingEnabled) "Location sharing ON" else "Location sharing paused",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp
                        )
                        Text(
                            if (trackingEnabled) "You can still hide yourself from one specific person below."
                            else "No new location is being uploaded from this phone.",
                            color = Color.White.copy(alpha = .78f),
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = trackingEnabled,
                        onCheckedChange = { onToggleTracking() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Mint
                        )
                    )
                }
            }
        }

        item { SectionTitle("Who can see me", "Person-by-person control") }

        sharingError?.let { message ->
            item {
                Surface(color = RoseSoft, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Rose, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(message, color = Color(0xFF8E2B3D), fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { reloadSharing() } }) {
                            Icon(Icons.Default.Refresh, "Retry", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        if (sharingBusy && sharingRules.isEmpty()) {
            item {
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading family privacy controls…", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        } else if (sharingRules.isEmpty()) {
            item {
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Default.GroupOff, SurfaceSoft, Muted)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("No other joined member yet", fontWeight = FontWeight.Bold)
                            Text("Individual controls appear here after someone joins your family.", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        } else {
            items(sharingRules, key = { it.viewerMemberId }) { rule ->
                PremiumCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).background(avatarColor(rule.viewerMemberId), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                rule.viewerName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rule.viewerName, fontWeight = FontWeight.Black, fontSize = 14.sp)
                            Text(
                                if (rule.locationEnabled) "Can see your live location" else "Your location is hidden from this person",
                                color = if (rule.locationEnabled) Muted else Rose,
                                fontSize = 9.8.sp
                            )
                        }
                        Switch(
                            checked = rule.locationEnabled,
                            onCheckedChange = { updateRule(rule, location = it) }
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Speed", fontWeight = FontWeight.Bold, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = rule.speedEnabled,
                            enabled = rule.locationEnabled && shareSpeed,
                            onCheckedChange = { updateRule(rule, speed = it) },
                            modifier = Modifier.scale(.78f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Battery", fontWeight = FontWeight.Bold, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = rule.batteryEnabled,
                            enabled = shareBattery,
                            onCheckedChange = { updateRule(rule, battery = it) },
                            modifier = Modifier.scale(.78f)
                        )
                    }
                }
            }
        }

        item { SectionTitle("What this phone shares", "Global controls") }

        item {
            PremiumCard {
                SettingRow(
                    Icons.Default.Speed,
                    "Driving speed",
                    "Current, average and maximum speed",
                    shareSpeed
                ) {
                    shareSpeed = it
                    prefs.edit().putBoolean("share_speed", it).apply()
                }
                SoftDivider()
                SettingRow(
                    Icons.Default.BatteryChargingFull,
                    "Battery status",
                    "Battery percentage during travel",
                    shareBattery
                ) {
                    shareBattery = it
                    prefs.edit().putBoolean("share_battery", it).apply()
                }
            }
        }

        item { SectionTitle("Speed alert", "Family threshold") }
        item {
            PremiumCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Default.Speed, AmberSoft, Amber, 38)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Notify above", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        Text("Sustained speed only — GPS spikes are filtered.", color = Muted, fontSize = 9.5.sp)
                    }
                    StatusPill(speedLimit.toString() + " km/h", PurpleSoft, Purple)
                }
                Slider(
                    value = speedLimit.toFloat(),
                    onValueChange = { speedLimit = ((it / 5).toInt() * 5).coerceIn(40, 140) },
                    onValueChangeFinished = { prefs.edit().putInt("speed_limit", speedLimit).apply() },
                    valueRange = 40f..140f,
                    steps = 19
                )
                Text("This is your family alert threshold, not a legal road-speed claim.", color = Muted, fontSize = 9.5.sp)
            }
        }

        item { SectionTitle("Permission health") }
        item {
            PremiumCard {
                HealthRow("Precise location", fine, if (fine) "Ready" else "Required for accurate live map")
                SoftDivider()
                HealthRow("Background location", background, if (background) "Ready" else "Needed for reliable trips and place alerts")
                SoftDivider()
                HealthRow("Notifications", notification, if (notification) "Ready" else "Enable to receive arrival and trip alerts")
                SoftDivider()
                HealthRow("Usage Access", snapshot.usageAccess, if (snapshot.usageAccess) "Optional access granted" else "Optional and off")
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + context.packageName)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Settings, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open Android permissions")
                }
            }
        }

        item {
            PremiumCard {
                Text("Family account", fontWeight = FontWeight.Black)
                Text(
                    AppPrefs.familyName(context) + " • " + if (AppPrefs.isOwner(context)) "Owner" else "Member",
                    color = Muted,
                    fontSize = 10.5.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Logout, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Leave this family on this phone")
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, PurpleSoft, Purple, 38)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Text(subtitle, color = Muted, fontSize = 9.5.sp, maxLines = 1)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HealthRow(title: String, good: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (good) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = if (good) Mint else Amber, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
            Text(detail, color = Muted, fontSize = 9.3.sp)
        }
    }
}
