package com.familyconnect.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(46.dp).background(
                Brush.linearGradient(listOf(Purple, Color(0xFF8B83FF))),
                RoundedCornerShape(15.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.FamilyRestroom, null, tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
            Text(subtitle, fontSize = 11.5.sp, color = Muted)
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Icon(Icons.Default.NotificationsNone, null, modifier = Modifier.padding(10.dp).size(21.dp))
        }
    }
}

@Composable
fun SectionTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = Muted, fontSize = 10.5.sp) }
    }
}

@Composable
fun StatusPill(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = CircleShape) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp
        )
    }
}

@Composable
fun IconTile(icon: ImageVector, bg: Color, fg: Color, size: Int = 42) {
    Box(
        Modifier.size(size.dp).background(bg, RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val clickable = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Surface(
        modifier = clickable,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .48f)),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

@Composable
fun SoftDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .42f))
}

fun avatarColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF6159D9),
        Color(0xFFE1698B),
        Color(0xFF2F9A7C),
        Color(0xFFD48735),
        Color(0xFF4B83D1),
        Color(0xFF9B5CC4)
    )
    return palette[kotlin.math.abs(seed.hashCode()) % palette.size]
}

fun ageText(updatedAt: Long): String {
    if (updatedAt <= 0L) return "Never updated"
    val sec = ((System.currentTimeMillis() - updatedAt) / 1000L).coerceAtLeast(0L)
    return when {
        sec < 10 -> "Now"
        sec < 60 -> sec.toString() + " sec ago"
        sec < 3600 -> (sec / 60).toString() + " min ago"
        else -> (sec / 3600).toString() + " hr ago"
    }
}
