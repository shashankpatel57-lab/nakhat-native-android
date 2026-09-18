package in.familyconnect.app.model

enum class AppScreen(val label: String) { Home("Home"), Map("Map"), Family("Family"), Safety("Safety"), Profile("Profile") }

data class MemberUi(
    val id: String,
    val name: String,
    val initial: String,
    val place: String,
    val detail: String,
    val battery: Int,
    val speed: Int? = null,
    val eta: String? = null,
    val isOnline: Boolean = true,
    val isPreview: Boolean = false
)

data class DeviceSnapshot(
    val battery: Int = 0,
    val charging: Boolean = false,
    val network: String = "Unknown",
    val usageAccess: Boolean = false,
    val lastUsedApp: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Int = 0,
    val locationAgeSeconds: Long? = null
)
