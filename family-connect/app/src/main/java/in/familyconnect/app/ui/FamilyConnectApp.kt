package com.familyconnect.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.familyconnect.app.ExternalDestinationShare
import com.familyconnect.app.cloud.CloudState
import com.familyconnect.app.cloud.FamilyCloud
import com.familyconnect.app.data.DeviceRepository
import com.familyconnect.app.model.AppScreen
import com.familyconnect.app.model.DeviceSnapshot
import com.familyconnect.app.state.AppPrefs
import com.familyconnect.app.tracking.LocationTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FamilyConnectApp() {
    val context = LocalContext.current
    var sessionVersion by rememberSaveable { mutableIntStateOf(0) }
    val configured = remember(sessionVersion) { AppPrefs.setupComplete(context) }

    if (!configured) {
        SetupScreen(onReady = { sessionVersion++ })
    } else {
        FamilyShell(onReset = {
            context.stopService(Intent(context, LocationTrackingService::class.java))
            AppPrefs.resetAll(context)
            sessionVersion++
        })
    }
}

@Composable
private fun SetupScreen(onReady: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf("welcome") }
    var name by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFEAF3FF), Color(0xFFF5FAFC), Color.White)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color.White.copy(alpha = .88f),
                shadowElevation = 10.dp
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(58.dp).background(
                                Brush.linearGradient(listOf(Color(0xFF2F6FED), Color(0xFF2BB3C0))),
                                RoundedCornerShape(18.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Family Connect", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Ink)
                            Text("Private family coordination", color = Muted, fontSize = 11.5.sp)
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    when (mode) {
                        "welcome" -> {
                            Text(
                                "Stay connected without repeated calls.",
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                color = Ink
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Create a private family or join one with a simple 6-digit code. Only real joined members appear.",
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = { mode = "create"; error = null },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.Groups, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Create a Family", fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(10.dp))
                            FilledTonalButton(
                                onClick = { mode = "join"; error = null },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Join with 6-digit Code", fontWeight = FontWeight.Bold)
                            }
                        }

                        "create" -> {
                            Text("Create your family", fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text("You can invite members after setup.", color = Muted, fontSize = 10.5.sp)
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(60) },
                                label = { Text("Your name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(15.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = family,
                                onValueChange = { family = it.take(80) },
                                label = { Text("Family name") },
                                placeholder = { Text("e.g. Patel Family") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(15.dp)
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                enabled = !busy && name.trim().length >= 2 && family.trim().length >= 2,
                                onClick = {
                                    busy = true
                                    error = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            FamilyCloud.createFamily(context, name.trim(), family.trim())
                                        }
                                        busy = false
                                        result.onSuccess { onReady() }
                                            .onFailure { error = it.message ?: "Could not create family" }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Text("Create & Continue", fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { mode = "welcome" },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) { Text("Back") }
                        }

                        else -> {
                            Text("Join a family", fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text("Ask a family member for the current 6-digit invite code.", color = Muted, fontSize = 10.5.sp)
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(60) },
                                label = { Text("Your name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(15.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                                label = { Text("6-digit family code") },
                                placeholder = { Text("123456") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 5.sp
                                ),
                                shape = RoundedCornerShape(15.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Codes expire after 10 minutes and can be used once.",
                                color = Muted,
                                fontSize = 9.8.sp
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                enabled = !busy && name.trim().length >= 2 && code.length == 6,
                                onClick = {
                                    busy = true
                                    error = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            FamilyCloud.joinShortCode(context, name.trim(), code)
                                        }
                                        busy = false
                                        result.onSuccess { onReady() }
                                            .onFailure { error = it.message ?: "Could not join family" }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Text("Join Family", fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { mode = "welcome" },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) { Text("Back") }
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Surface(color = RoseSoft, shape = RoundedCornerShape(13.dp)) {
                            Text(it, Modifier.padding(11.dp), color = Color(0xFF9D2639), fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = Mint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    "You control who can see your location, speed and battery.",
                    color = Muted,
                    fontSize = 10.3.sp
                )
            }
        }
    }
}

@Composable
private fun FamilyShell(onReset: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screenName by rememberSaveable { mutableStateOf(AppScreen.Home.name) }
    val screen = AppScreen.valueOf(screenName)
    val sharedDestinationText by ExternalDestinationShare.text.collectAsState()
    var snapshot by remember { mutableStateOf(DeviceRepository.snapshot(context)) }
    var cloudState by remember { mutableStateOf<CloudState?>(null) }
    var cloudBusy by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var trackingEnabled by rememberSaveable {
        mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("location_sharing", false))
    }

    LaunchedEffect(sharedDestinationText) {
        if (sharedDestinationText != null) screenName = AppScreen.Home.name
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("location_sharing", true).apply()
            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
            trackingEnabled = true
        }
    }

    val startTracking: () -> Unit = {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("location_sharing", true).apply()
            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
            trackingEnabled = true
        } else {
            val req = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) req += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(req.toTypedArray())
        }
    }

    val stopTracking: () -> Unit = {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("location_sharing", false).apply()
        context.stopService(Intent(context, LocationTrackingService::class.java))
        trackingEnabled = false
    }

    val refreshCloud: suspend (Boolean) -> Unit = { showBusy ->
        if (showBusy) cloudBusy = true
        val result = withContext(Dispatchers.IO) { FamilyCloud.pull(context) }
        if (showBusy) cloudBusy = false
        result.onSuccess {
            cloudState = it
            AppPrefs.replacePlaces(context, it.places)
            cloudError = null
        }.onFailure {
            cloudError = it.message ?: "Family sync unavailable"
        }
    }

    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (trackingEnabled && fine) {
            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
        }
        refreshCloud(true)
    }

    LaunchedEffect(screenName) {
        while (true) {
            refreshCloud(false)
            delay(if (screen == AppScreen.Map) 2200L else 5000L)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = DeviceRepository.snapshot(context)
            delay(1000L)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            PremiumBottomBar(screen) { screenName = it.name }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                AppScreen.Home -> HomeDashboard(
                    snapshot = snapshot,
                    cloud = cloudState,
                    cloudBusy = cloudBusy,
                    cloudError = cloudError,
                    trackingEnabled = trackingEnabled,
                    onToggleTracking = { if (trackingEnabled) stopTracking() else startTracking() },
                    onRefresh = { scope.launch { refreshCloud(true) } },
                    onOpenMap = { screenName = AppScreen.Map.name },
                    onOpenFamily = { screenName = AppScreen.Family.name }
                )
                AppScreen.Map -> LiveFamilyMap(
                    snapshot = snapshot,
                    cloud = cloudState,
                    trackingEnabled = trackingEnabled,
                    onStartTracking = startTracking
                )
                AppScreen.Family -> FamilyAndPlacesScreen(
                    snapshot = snapshot,
                    cloud = cloudState,
                    onRefresh = { scope.launch { refreshCloud(true) } }
                )
                AppScreen.Safety -> SafetyCentre(
                    snapshot = snapshot,
                    onStartTracking = startTracking,
                    onRefresh = { scope.launch { refreshCloud(true) } }
                )
                AppScreen.Profile -> PrivacyProfile(
                    snapshot = snapshot,
                    trackingEnabled = trackingEnabled,
                    onToggleTracking = { if (trackingEnabled) stopTracking() else startTracking() },
                    onReset = onReset
                )
            }
        }
    }
}

@Composable
private fun PremiumBottomBar(selected: AppScreen, onSelect: (AppScreen) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .navigationBarsPadding()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            tonalElevation = 1.dp
        ) {
            NavigationBar(
                modifier = Modifier.height(66.dp),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                val items = listOf(
                    Triple(AppScreen.Home, Icons.Default.Home, "Home"),
                    Triple(AppScreen.Map, Icons.Default.Map, "Map"),
                    Triple(AppScreen.Family, Icons.Default.Groups, "Family"),
                    Triple(AppScreen.Safety, Icons.Default.Security, "Safety"),
                    Triple(AppScreen.Profile, Icons.Default.Person, "You")
                )
                items.forEach { item ->
                    NavigationBarItem(
                        selected = selected == item.first,
                        onClick = { onSelect(item.first) },
                        icon = {
                            Icon(
                                item.second,
                                item.third,
                                modifier = Modifier.size(if (selected == item.first) 23.dp else 20.dp)
                            )
                        },
                        label = {
                            Text(
                                item.third,
                                fontSize = 9.sp,
                                fontWeight = if (selected == item.first) FontWeight.Black else FontWeight.Medium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Purple,
                            selectedTextColor = Purple,
                            indicatorColor = PurpleSoft,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    }
}
