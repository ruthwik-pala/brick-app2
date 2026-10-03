package com.example.brick

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.location.*
import java.io.File
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

const val DEFAULT_RANGE_M = 50f
const val GEOFENCE_MIN_M = 100f   // below this, Android geofences are too coarse to trust
const val MIN_SAMPLES = 3         // readings needed before we trust a position
const val ENOUGH_SAMPLES = 4      // stop early once we have this many good readings
const val MAX_FIX_AGE_S = 5.0     // ignore readings older than this
const val SAMPLE_MS = 12000L      // otherwise sample for up to this long
val RANGE_OPTIONS = listOf(25f, 50f, 100f)

// ---------- State ----------

object LockStore {
    private fun p(c: Context) = c.getSharedPreferences("brick", Context.MODE_PRIVATE)
    fun locked(c: Context) = p(c).getBoolean("locked", false)
    fun blocked(c: Context): Set<String> = p(c).getStringSet("blocked", emptySet())!!
    fun setBlocked(c: Context, s: Set<String>) = p(c).edit().putStringSet("blocked", s).apply()
    fun radius(c: Context) = p(c).getFloat("radius", DEFAULT_RANGE_M)
    fun setRadius(c: Context, r: Float) = p(c).edit().putFloat("radius", r).apply()

    fun setLock(c: Context, l: Location) = p(c).edit()
        .putBoolean("locked", true)
        .putLong("lat", java.lang.Double.doubleToRawLongBits(l.latitude))
        .putLong("lon", java.lang.Double.doubleToRawLongBits(l.longitude)).apply()

    fun anchor(c: Context): Location = Location("anchor").apply {
        latitude = java.lang.Double.longBitsToDouble(p(c).getLong("lat", 0))
        longitude = java.lang.Double.longBitsToDouble(p(c).getLong("lon", 0))
    }

    fun clear(c: Context) = p(c).edit().putBoolean("locked", false).apply()
}

// ---------- Locking logic ----------

object LockController {
    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 0, Intent(c, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    private fun hasPrecise(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isFresh(l: Location) =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000.0 <= MAX_FIX_AGE_S

    @Suppress("DEPRECATION")
    private fun isMock(l: Location) =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    /** Accuracy-weighted average of several readings, so one stray reading can't move the spot. */
    private fun center(fixes: List<Location>): Location {
        var wSum = 0.0; var lat = 0.0; var lon = 0.0
        for (f in fixes) {
            val w = 1.0 / (f.accuracy.toDouble() * f.accuracy + 1.0)
            wSum += w; lat += f.latitude * w; lon += f.longitude * w
        }
        return Location("center").apply {
            latitude = lat / wSum
            longitude = lon / wSum
            accuracy = fixes.minOf { it.accuracy }
        }
    }

    /**
     * Collects fresh GPS readings for up to SAMPLE_MS. Only readings at least as accurate as
     * maxAccuracy count, and at least MIN_SAMPLES of them are needed. Stale and mock readings
     * are thrown away. Calls onFixes with the good readings, or explains why there aren't enough.
     */
    @SuppressLint("MissingPermission")
    private fun collectFixes(
        c: Context, maxAccuracy: Float, done: (String) -> Unit, onFixes: (List<Location>) -> Unit
    ) {
        if (!hasPrecise(c)) {
            return done("Turn on Precise location for Brick in the app's permission settings.")
        }
        val client = LocationServices.getFusedLocationProviderClient(c)
        val handler = Handler(Looper.getMainLooper())
        val samples = mutableListOf<Location>()
        var sawMock = false
        var finished = false
        lateinit var cb: LocationCallback

        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            client.removeLocationUpdates(cb)
            val good = samples.filter { it.accuracy <= maxAccuracy }
            when {
                sawMock -> done("Mock locations aren't allowed. Turn off any fake GPS app.")
                samples.isEmpty() -> done("Couldn't get a GPS fix. Go outside or near a window and try again.")
                good.size < MIN_SAMPLES -> {
                    val best = samples.minOf { it.accuracy }.toInt()
                    done("GPS is only accurate to ±$best m right now. Move outside or near a window and try again.")
                }
                else -> onFixes(good)
            }
        }

        cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                for (l in r.locations) {
                    if (isMock(l)) sawMock = true
                    else if (isFresh(l)) samples.add(l)
                }
                if (samples.count { it.accuracy <= maxAccuracy } >= ENOUGH_SAMPLES) finish()
            }
        }
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L).build()
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
                .addOnFailureListener {
                    if (!finished) {
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        done("Couldn't read your location. Check that location is on.")
                    }
                }
            handler.postDelayed({ finish() }, SAMPLE_MS)
        } catch (e: SecurityException) {
            finished = true
            done("Location permission is missing. Allow it in the app's settings.")
        }
    }

    @SuppressLint("MissingPermission")
    fun lockHere(c: Context, range: Float, done: (String) -> Unit) {
        if (LockStore.blocked(c).isEmpty()) return done("Pick at least one app first.")
        collectFixes(c, range, done) { fixes ->
            val loc = center(fixes)
            LockStore.setRadius(c, range)
            LockStore.setLock(c, loc)
            val acc = loc.accuracy.toInt()
            val r = range.toInt()
            if (range < GEOFENCE_MIN_M) {
                done("Locked (GPS ±$acc m). Come back within $r m and tap Unlock.")
                return@collectFixes
            }
            try {
                val fence = Geofence.Builder().setRequestId("anchor")
                    .setCircularRegion(loc.latitude, loc.longitude, range)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER).build()
                val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
                LocationServices.getGeofencingClient(c).addGeofences(req, pi(c))
                    .addOnSuccessListener { done("Locked (GPS ±$acc m). Come back within $r m to unlock.") }
                    .addOnFailureListener { done("Locked (GPS ±$acc m). Auto-unlock is off, so tap Unlock when you're back.") }
            } catch (e: SecurityException) {
                done("Locked (GPS ±$acc m). Auto-unlock is off, so tap Unlock when you're back.")
            }
        }
    }

    /** Unlocks only if the MEDIAN of several fresh readings is inside the range. */
    fun tryUnlock(c: Context, done: (String) -> Unit) {
        val range = LockStore.radius(c)
        collectFixes(c, range, done) { fixes ->
            val anchor = LockStore.anchor(c)
            val ds = fixes.map { it.distanceTo(anchor) }.sorted()
            val d = ds[ds.size / 2]
            if (d <= range) { unlock(c); done("Unlocked.") }
            else done("You're about ${d.toInt()} m from the lock spot. Get within ${range.toInt()} m.")
        }
    }

    fun unlock(c: Context) {
        LockStore.clear(c)
        LocationServices.getGeofencingClient(c).removeGeofences(pi(c))
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val e = GeofencingEvent.fromIntent(i) ?: return
        // Auto-unlock only in the wide setting; tighter ranges are checked by GPS when you tap Unlock.
        if (!e.hasError() && e.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER &&
            LockStore.radius(c) >= GEOFENCE_MIN_M) LockController.unlock(c)
    }
}

/** Sends the user home whenever a blocked app comes to the foreground while locked. */
class BlockerService : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (LockStore.locked(this) && pkg in LockStore.blocked(this)) performGlobalAction(GLOBAL_ACTION_HOME)
    }
    override fun onInterrupt() {}
}

// ---------- UI ----------

private val Fog = Color(0xFFE8ECEF)
private val Ink = Color(0xFF16202A)
private val InkSoft = Color(0xFF5B6772)
private val BrickRed = Color(0xFFB23A2E)
private val BrickDark = Color(0xFF8C2B22)
private val Line = Color(0xFFC3CCD4)

private val SOCIAL = setOf(
    "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.twitter.android",
    "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.snapchat.android",
    "com.reddit.frontpage", "com.google.android.youtube", "com.pinterest",
    "com.linkedin.android", "org.telegram.messenger", "com.discord"
)

@Composable
fun BrickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = BrickRed, onPrimary = Color.White,
            background = Fog, surface = Fog, onSurface = Ink, outline = Line
        ),
        content = content
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION")
        window.apply {
            statusBarColor = 0xFFE8ECEF.toInt()
            navigationBarColor = 0xFFE8ECEF.toInt()
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // Background location must be requested separately, after fine location.
        val bg = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val fine = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            bg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        fine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { BrickTheme { Screen() } }
    }
}

/** A wall of bricks that builds itself, row by row, when you lock. */
@Composable
fun BrickWall(locked: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = if (locked) 1f else 0f,
        animationSpec = tween(if (locked) 1200 else 450),
        label = "wall"
    )
    Canvas(modifier) {
        val rows = 7
        val cols = 4
        val gap = 6.dp.toPx()
        val bh = (size.height - gap * (rows - 1)) / rows
        val bw = (size.width - gap * (cols - 1)) / cols
        val total = rows * (cols + 1)
        val radius = CornerRadius(5.dp.toPx())
        clipRect(0f, 0f, size.width, size.height) {
            for (r in 0 until rows) {
                val off = if (r % 2 == 1) -(bw + gap) / 2f else 0f
                val y = size.height - (r + 1) * bh - r * gap
                for (c in 0..cols) {
                    val i = r * (cols + 1) + c
                    val topLeft = Offset(off + c * (bw + gap), y)
                    val sz = Size(bw, bh)
                    val fill = (progress * total - i).coerceIn(0f, 1f)
                    drawRoundRect(Line, topLeft, sz, radius, style = Stroke(width = 1.5.dp.toPx()))
                    if (fill > 0f) {
                        val shade = ((i * 37) % 5) / 5f * 0.55f
                        drawRoundRect(lerp(BrickRed, BrickDark, shade).copy(alpha = fill), topLeft, sz, radius)
                    }
                }
            }
        }
    }
}

fun formatDistance(m: Float): String =
    if (m < 1000f) "${m.toInt()} m" else String.format("%.1f km", m / 1000f)

/** OpenStreetMap view: red pin and circle at the lock spot, blue dot for you. */
@Composable
fun LockMap(anchor: Location, range: Float, me: Location?, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val fitted = remember { booleanArrayOf(false) }
    remember {
        Configuration.getInstance().apply {
            userAgentValue = c.packageName
            osmdroidBasePath = File(c.filesDir, "osmdroid")
            osmdroidTileCache = File(c.filesDir, "osmdroid/tiles")
        }
        true
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(17.0)
                controller.setCenter(GeoPoint(anchor.latitude, anchor.longitude))
            }
        },
        update = { map ->
            val a = GeoPoint(anchor.latitude, anchor.longitude)
            map.overlays.clear()
            map.overlays.add(Polygon(map).apply {
                points = Polygon.pointsAsCircle(a, range.toDouble())
                fillPaint.color = 0x22B23A2E
                outlinePaint.color = 0xFFB23A2E.toInt()
                outlinePaint.strokeWidth = 3f
            })
            map.overlays.add(Marker(map).apply {
                position = a
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Lock spot"
            })
            if (me != null) {
                val p = GeoPoint(me.latitude, me.longitude)
                map.overlays.add(Marker(map).apply {
                    position = p
                    icon = ContextCompat.getDrawable(map.context, R.drawable.me_dot)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "You"
                })
                if (!fitted[0] && map.width > 0) {
                    if (me.distanceTo(anchor) < 25f) {
                        map.controller.setZoom(18.0)
                        map.controller.setCenter(a)
                    } else {
                        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(listOf(a, p)), false, 120)
                    }
                    fitted[0] = true
                }
            }
            map.overlays.add(CopyrightOverlay(map.context))
            map.invalidate()
        },
        onRelease = { it.onDetach() }
    )
}

@Composable
fun RangePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected) Ink else Color.Transparent)
            .border(1.dp, if (selected) Ink else Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, color = if (selected) Color.White else Ink, fontSize = 14.sp) }
}

@Composable
fun AppIcon(pkg: String, size: Dp) {
    val c = LocalContext.current
    val img = remember(pkg) {
        runCatching { c.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (img != null) {
        Image(
            bitmap = img, contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.22f))
        )
    } else Spacer(Modifier.size(size))
}

@Composable
fun AppRow(pkg: String, name: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(pkg, 40.dp)
        Spacer(Modifier.width(14.dp))
        Text(name, Modifier.weight(1f), color = Ink, fontSize = 16.sp)
        Checkbox(
            checked = checked, onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = BrickRed)
        )
    }
}

@Composable
fun Screen() {
    val c = LocalContext.current
    val pm = c.packageManager
    val haptic = LocalHapticFeedback.current
    val apps = remember {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != c.packageName }.distinct().sortedBy { it.second.lowercase() }
    }
    var sel by remember { mutableStateOf(LockStore.blocked(c)) }
    var locked by remember { mutableStateOf(LockStore.locked(c)) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(LockStore.radius(c)) }
    var cooldown by remember { mutableStateOf(0) }
    var myLoc by remember { mutableStateOf<Location?>(null) }

    // Short pause after a failed unlock, so the button can't just be hammered.
    LaunchedEffect(cooldown) {
        if (cooldown > 0) { delay(1000); cooldown -= 1 }
    }

    // Live position while locked, for the map. Stops when unlocked or the screen closes.
    DisposableEffect(locked) {
        val client = LocationServices.getFusedLocationProviderClient(c)
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { myLoc = it } }
        }
        if (locked) {
            try {
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
                client.requestLocationUpdates(req, cb, Looper.getMainLooper())
            } catch (e: SecurityException) { }
        }
        onDispose { client.removeLocationUpdates(cb) }
    }
    val wallHeight by animateDpAsState(if (locked) 96.dp else 130.dp, tween(500), label = "wallHeight")

    Column(Modifier.fillMaxSize().background(Fog).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(
            if (locked) "Apps locked" else "Lock your apps to this spot",
            color = Ink, fontSize = 30.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (locked) "They open again when you're back where you locked them."
            else "Choose what to block, then lock. Come back here to open them.",
            color = InkSoft, fontSize = 15.sp, lineHeight = 21.sp
        )
        Spacer(Modifier.height(20.dp))
        BrickWall(locked, Modifier.fillMaxWidth().height(wallHeight))
        Spacer(Modifier.height(20.dp))

        if (locked) {
            val anchor = remember { LockStore.anchor(c) }
            val lockRange = LockStore.radius(c)
            val away = myLoc?.distanceTo(anchor)
            Text(
                when {
                    away == null -> "Finding where you are…"
                    away <= lockRange -> "You're at the lock spot"
                    else -> "You're ${formatDistance(away)} from the lock spot"
                },
                color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                "Unlock works inside the red circle (${lockRange.toInt()} m). The blue dot is you.",
                color = InkSoft, fontSize = 13.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(10.dp))
            LockMap(anchor, lockRange, myLoc, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)))
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sel.toList()) { AppIcon(it, 32.dp) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${sel.size} selected", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { sel = sel + apps.map { it.first }.filter { it in SOCIAL } }) {
                    Text("Select social apps", color = BrickRed)
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search apps") }, singleLine = true,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrickRed, unfocusedBorderColor = Line, cursorColor = BrickRed
                )
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(apps.filter { it.second.contains(query, ignoreCase = true) }, key = { it.first }) { (pkg, name) ->
                    AppRow(pkg, name, pkg in sel) { sel = if (pkg in sel) sel - pkg else sel + pkg }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Unlock range", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                RANGE_OPTIONS.forEach { r ->
                    RangePill("${r.toInt()} m", r == range) { range = r }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Tighter is more exact but needs a clear GPS signal. At 100 m it also unlocks by itself when you arrive.",
                color = InkSoft, fontSize = 13.sp, lineHeight = 18.sp
            )
        }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, color = InkSoft, fontSize = 14.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                busy = true
                if (locked) {
                    LockController.tryUnlock(c) { m ->
                        msg = m; locked = LockStore.locked(c); busy = false
                        if (locked) cooldown = 10
                    }
                } else {
                    LockStore.setBlocked(c, sel)
                    LockController.lockHere(c, range) { m ->
                        msg = m; locked = LockStore.locked(c); busy = false
                        if (locked) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            },
            enabled = !busy && cooldown == 0,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (locked) Ink else BrickRed,
                disabledContainerColor = InkSoft
            )
        ) {
            Text(
                when {
                    busy -> "Finding your spot…"
                    cooldown > 0 -> "Try again in ${cooldown}s"
                    locked -> "Unlock here"
                    else -> "Lock here"
                },
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White
            )
        }
    }
}
