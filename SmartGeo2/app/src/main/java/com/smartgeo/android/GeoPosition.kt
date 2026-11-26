package com.smartgeo.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.UUID

object GeoPosition {
    private const val TAG = "SmartGeoGPS"
    private const val MAX_ACCURACY = 50f
    private const val MAX_AGE_NANOS = 10_000_000_000L
    private const val TIMEOUT_MS = 45_000L

    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Indicateur de développement, pas une preuve de sécurité.
    fun isEmulator(): Boolean {
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
        val model = Build.MODEL.lowercase(Locale.ROOT)
        return hardware.contains("ranchu") || hardware.contains("goldfish") ||
            model.contains("sdk_gphone") || model.contains("android sdk built for") ||
            fingerprint.startsWith("generic") || fingerprint.contains("vbox")
    }

    private fun valid(location: Location, started: Long): Boolean {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.hasAccuracy() && location.accuracy.isFinite() &&
            location.accuracy > 0f && location.accuracy <= MAX_ACCURACY &&
            location.elapsedRealtimeNanos >= started - 2_000_000_000L &&
            age in 0L..MAX_AGE_NANOS &&
            (isEmulator() || !LocationCompat.isMock(location))
    }

    // L'émulateur utilise son fournisseur GPS natif, piloté par Extended Controls/ADB.
    // Sur un téléphone, Fused Location Provider combine les capteurs disponibles.
    @Suppress("MissingPermission")
    private fun nativeFixes(manager: LocationManager) = callbackFlow<Location> {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(location) }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                close(IllegalStateException("GPS désactivé."))
            }
            @Deprecated("Legacy callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f,
            listener, Looper.getMainLooper())
        awaitClose { manager.removeUpdates(listener) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Suppress("MissingPermission")
    private fun fusedFixes(context: Context) = callbackFlow<Location> {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateAgeMillis(0L)
            .setWaitForAccurateLocation(true)
            .build()
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it) }
            }
        }
        client.requestLocationUpdates(request, listener, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
            .addOnSuccessListener {
                if (isClosedForSend) client.removeLocationUpdates(listener)
            }
        awaitClose { client.removeLocationUpdates(listener) }
    }

    suspend fun capture(context: Context): JSONObject {
        val app = context.applicationContext
        check(allowed(app)) { "Autorisez la localisation précise dans Android." }
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        check(enabled) { "Activez la localisation de l'appareil." }
        val emulator = isEmulator()
        if (emulator) check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            "Activez le GPS de l'émulateur."
        }
        val started = SystemClock.elapsedRealtimeNanos()
        val location = withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(TIMEOUT_MS) {
                val fixes = if (emulator) nativeFixes(manager) else fusedFixes(app)
                fixes.first { valid(it, started) }
            }
        } ?: error("Aucun relevé récent de précision ≤ 50 m. " +
            if (emulator) "Vérifiez le GPS simulé de l'émulateur." else "Vérifiez le signal GPS.")
        check(allowed(app)) { "Permission GPS retirée." }
        Log.i(TAG, "source=${location.provider}, emulateur=$emulator, " +
            "mock=${LocationCompat.isMock(location)}, precision=${location.accuracy} m")
        val payload = JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy", location.accuracy.toDouble())
            .put("recordedAt", Instant.ofEpochMilli(location.time).toString())
            .put("clientPositionId", UUID.randomUUID().toString())
        if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f)
            payload.put("speed", location.speed.toDouble())
        if (location.hasBearing() && location.bearing.isFinite() && location.bearing in 0f..360f)
            payload.put("bearing", location.bearing.toDouble())
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level in 0..scale && scale > 0) payload.put("batteryLevel", level * 100 / scale)
        return payload
    }
}
