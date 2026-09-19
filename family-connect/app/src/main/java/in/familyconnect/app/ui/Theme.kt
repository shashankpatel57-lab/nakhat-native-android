package com.familyconnect.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF122033)
val Muted = Color(0xFF68768A)

// V6 visual system: clean map-blue + teal, less purple and less "prototype" styling.
val Purple = Color(0xFF2F6FED)
val PurpleSoft = Color(0xFFE8F0FF)
val Mint = Color(0xFF18A477)
val MintSoft = Color(0xFFE4F6EF)
val Rose = Color(0xFFE64B61)
val RoseSoft = Color(0xFFFFE9ED)
val Amber = Color(0xFFF59E0B)
val AmberSoft = Color(0xFFFFF4D9)
val SurfaceSoft = Color(0xFFF1F5F9)
val DarkSurface = Color(0xFF17202E)
val DarkCard = Color(0xFF202C3D)

val Sky = Color(0xFF2BB3C0)
val SkySoft = Color(0xFFE4F8FA)
val Navy = Color(0xFF163A63)
val Success = Color(0xFF1F9D68)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleSoft,
    onPrimaryContainer = Navy,
    secondary = Sky,
    onSecondary = Color.White,
    secondaryContainer = SkySoft,
    background = Color(0xFFF4F7FB),
    surface = Color.White,
    surfaceVariant = SurfaceSoft,
    onBackground = Ink,
    onSurface = Ink,
    outline = Color(0xFFDCE3EB),
    error = Rose
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CB2FF),
    onPrimary = Color(0xFF062A5B),
    primaryContainer = Color(0xFF153C71),
    onPrimaryContainer = Color(0xFFE8F0FF),
    secondary = Color(0xFF70D4DC),
    onSecondary = Color(0xFF063D44),
    background = Color(0xFF0E1622),
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onBackground = Color(0xFFF3F7FC),
    onSurface = Color(0xFFF3F7FC),
    outline = Color(0xFF3A485C),
    error = Color(0xFFFF91A1)
)

@Composable
fun FamilyConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
