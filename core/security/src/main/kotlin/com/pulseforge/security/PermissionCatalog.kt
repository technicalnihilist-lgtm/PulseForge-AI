package com.pulseforge.security

import com.pulseforge.model.Severity

/** A curated view of Android's runtime permission model, used to score privacy risk. */
object PermissionCatalog {

    data class Entry(
        val group: String,
        val severity: Severity,
        val rationale: String,
        val restrictedOnPlay: Boolean = false
    )

    private const val P = "android.permission."

    private val entries: Map<String, Entry> = buildMap {
        fun add(name: String, group: String, severity: Severity, rationale: String, restricted: Boolean = false) {
            put(P + name, Entry(group, severity, rationale, restricted))
        }

        add("READ_SMS", "SMS", Severity.CRITICAL, "Reads the user's text messages. Google Play restricts this to default SMS handlers and a small set of approved use cases.", true)
        add("RECEIVE_SMS", "SMS", Severity.CRITICAL, "Intercepts incoming messages, including one-time passcodes.", true)
        add("SEND_SMS", "SMS", Severity.CRITICAL, "Sends messages that may incur cost without visible user action.", true)
        add("RECEIVE_MMS", "SMS", Severity.HIGH, "Reads multimedia messages.", true)
        add("READ_CALL_LOG", "Call log", Severity.CRITICAL, "Reads call history, which reveals social graph and timing.", true)
        add("WRITE_CALL_LOG", "Call log", Severity.HIGH, "Modifies call history.", true)
        add("PROCESS_OUTGOING_CALLS", "Call log", Severity.HIGH, "Intercepts outgoing calls.", true)

        add("READ_CONTACTS", "Contacts", Severity.HIGH, "Reads the address book, a high-value personal data set.")
        add("WRITE_CONTACTS", "Contacts", Severity.MEDIUM, "Modifies the address book.")
        add("GET_ACCOUNTS", "Accounts", Severity.MEDIUM, "Enumerates accounts on the device.")

        add("ACCESS_FINE_LOCATION", "Location", Severity.HIGH, "Precise location. Frequently combined with background access to track users.")
        add("ACCESS_COARSE_LOCATION", "Location", Severity.MEDIUM, "Approximate location.")
        add("ACCESS_BACKGROUND_LOCATION", "Location", Severity.CRITICAL, "Location while the app is not in use. Requires an explicit Play declaration.", true)

        add("CAMERA", "Camera", Severity.HIGH, "Access to camera frames and photos.")
        add("RECORD_AUDIO", "Microphone", Severity.HIGH, "Records audio from the microphone.")

        add("READ_PHONE_STATE", "Phone", Severity.MEDIUM, "Reads device identifiers and call state; a common tracking vector.")
        add("READ_PHONE_NUMBERS", "Phone", Severity.MEDIUM, "Reads the device phone number.")
        add("CALL_PHONE", "Phone", Severity.HIGH, "Places calls without the dialer.")

        add("READ_EXTERNAL_STORAGE", "Storage", Severity.MEDIUM, "Reads shared storage, which may contain other apps' exported data.")
        add("WRITE_EXTERNAL_STORAGE", "Storage", Severity.MEDIUM, "Writes to shared storage that other apps can read on older Android versions.")
        add("MANAGE_EXTERNAL_STORAGE", "Storage", Severity.CRITICAL, "Full filesystem access. Play requires a justified, reviewed use case.", true)

        add("READ_MEDIA_IMAGES", "Media", Severity.MEDIUM, "Reads all images on the device.")
        add("READ_MEDIA_VIDEO", "Media", Severity.MEDIUM, "Reads all videos on the device.")
        add("READ_MEDIA_AUDIO", "Media", Severity.MEDIUM, "Reads all audio files on the device.")

        add("BODY_SENSORS", "Sensors", Severity.HIGH, "Health-related sensor data.")
        add("ACTIVITY_RECOGNITION", "Activity", Severity.LOW, "Detects physical activity such as walking.")
        add("POST_NOTIFICATIONS", "Notifications", Severity.LOW, "Posts notifications (runtime permission since Android 13).")
        add("SYSTEM_ALERT_WINDOW", "Overlay", Severity.CRITICAL, "Draws over other apps; a classic tapjacking and overlay-phishing vector.", true)
        add("REQUEST_INSTALL_PACKAGES", "Install packages", Severity.CRITICAL, "Can prompt the user to install other APKs.", true)
        add("QUERY_ALL_PACKAGES", "Package visibility", Severity.HIGH, "Enumerates every installed app. Play restricts this to approved use cases.", true)
        add("PACKAGE_USAGE_STATS", "Usage access", Severity.HIGH, "Reads which apps the user opens and when.")
        add("WRITE_SETTINGS", "System settings", Severity.MEDIUM, "Modifies system settings.")
        add("BLUETOOTH_SCAN", "Nearby devices", Severity.MEDIUM, "Scans for nearby Bluetooth devices, usable for location inference.")
        add("BLUETOOTH_CONNECT", "Nearby devices", Severity.MEDIUM, "Connects to paired devices.")
        add("NEARBY_WIFI_DEVICES", "Nearby devices", Severity.MEDIUM, "Lists nearby Wi-Fi networks.")
        add("INTERNET", "Network", Severity.INFO, "Network access; normal permission, granted at install.")
        add("ACCESS_NETWORK_STATE", "Network", Severity.INFO, "Reads connectivity state.")
        add("FOREGROUND_SERVICE", "Foreground service", Severity.LOW, "Runs a foreground service.")
        add("RECEIVE_BOOT_COMPLETED", "Startup", Severity.LOW, "Starts after device boot.")
        add("WAKE_LOCK", "Power", Severity.LOW, "Keeps the CPU awake.")
        add("VIBRATE", "Other", Severity.INFO, "Controls vibration.")
        add("BILLING", "Billing", Severity.INFO, "In-app billing.")
    }

    fun entry(permission: String): Entry? = entries[permission]

    fun groupOf(permission: String): String? = entries[permission]?.group

    fun severityOf(permission: String): Severity = entries[permission]?.severity ?: Severity.INFO

    fun isDangerous(permission: String): Boolean {
        val entry = entries[permission] ?: return false
        return entry.severity != Severity.INFO
    }

    fun isRestricted(permission: String): Boolean = entries[permission]?.restrictedOnPlay == true

    data class GroupSummary(val group: String, val permissions: List<String>, val risk: Severity, val rationale: String)

    /** Groups of requested permissions, sorted by the worst severity in each group. */
    fun groups(permissions: List<String>): List<GroupSummary> = permissions
        .mapNotNull { p -> entries[p]?.let { Triple(it.group, p, it) } }
        .groupBy { it.first }
        .map { (group, list) ->
            GroupSummary(
                group = group,
                permissions = list.map { it.second }.sorted(),
                risk = list.maxByOrNull { it.third.severity.rank }!!.third.severity,
                rationale = list.first().third.rationale
            )
        }
        .sortedByDescending { it.risk.rank }

    fun unknownPermissions(permissions: List<String>): List<String> =
        permissions.filter { !it.startsWith("android.permission.") && entry(it) == null }
}
