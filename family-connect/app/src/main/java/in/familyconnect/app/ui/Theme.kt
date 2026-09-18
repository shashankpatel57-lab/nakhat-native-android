package com.familyconnect.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF171829)
val Muted = Color(0xFF77798C)
val Purple = Color(0xFF675DF5)
val PurpleSoft = Color(0xFFECEAFF)
val Mint = Color(0xFF21B87A)
val MintSoft = Color(0xFFE2F7EE)
val Rose = Color(0xFFFF5C75)
val RoseSoft = Color(0xFFFFE8EC)
val Amber = Color(0xFFF4A63A)
val AmberSoft = Color(0xFFFFF0D9)
val SurfaceSoft = Color(0xFFF5F6FA)
val DarkSurface = Color(0xFF171824)
val DarkCard = Color(0xFF202231)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = Mint,
    background = Color(0xFFF7F8FB),
    surface = Color.White,
    surfaceVariant = SurfaceSoft,
    onBackground = Ink,
    onSurface = Ink,
    outline = Color(0xFFE3E5ED)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9A93FF),
    onPrimary = Color(0xFF17133F),
    secondary = Color(0xFF61D8A8),
    background = Color(0xFF101119),
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onBackground = Color(0xFFF4F4FA),
    onSurface = Color(0xFFF4F4FA),
    outline = Color(0xFF353747)
)

@Composable
fun FamilyConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
