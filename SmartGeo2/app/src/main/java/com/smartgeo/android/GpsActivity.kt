
package com.smartgeo.android

import android.Manifest
import android.content.Intent
import android.location.Location
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class GpsActivity : AppCompatActivity() {

    private enum class GpsAction {
        NONE,
        CENTER,
        SEND,
        TRACK
    }

    private lateinit var api: SmartGeoApi
    private lateinit var mapView: MapView
    private lateinit var consentSwitch: SwitchMaterial
    private lateinit var statusView: TextView
    private lateinit var positionView: TextView
    private lateinit var schedulesView: TextView
    private lateinit var requestsContainer: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var trackingButton: MaterialButton
    private lateinit var pointsCountView: TextView
    private lateinit var mapHintView: TextView

    private var consentEnabled = false
    private var pendingAction = GpsAction.NONE
    private var pendingRequestId: Long? = null

    private var currentMarker: Marker? = null
    private var historyVersion = 0L
    private var historyRefresh: Job? = null
    private var captureInProgress = false
    private data class TrackPoint(val point: GeoPoint, val date: Instant?, val raw: JSONObject)

    private fun timestamp(value: Any?): Instant? = try {
        when (value) {
            is org.json.JSONArray -> LocalDateTime.of(value.getInt(0), value.getInt(1),
                value.getInt(2), value.optInt(3), value.optInt(4), value.optInt(5),
                value.optInt(6)).toInstant(ZoneOffset.UTC)
            is String -> runCatching { Instant.parse(value) }.getOrElse {
                LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
            }
            else -> null
        }
    } catch (_: Exception) { null }

    // Une rupture crée un nouveau trajet, sans effacer l'historique.
    private fun continuous(a: TrackPoint, b: TrackPoint): Boolean {
        val t1 = a.date ?: return false
        val t2 = b.date ?: return false
        val seconds = (t2.toEpochMilli() - t1.toEpochMilli()) / 1000.0
        if (seconds < 0 || seconds > 900) return false
        val distance = FloatArray(1)
        Location.distanceBetween(a.point.latitude, a.point.longitude,
            b.point.latitude, b.point.longitude, distance)
        return distance[0] <= 100.0 + seconds * (300.0 / 3.6)
    }

    private val locationPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {

            val action = pendingAction
            val requestId = pendingRequestId

            pendingAction = GpsAction.NONE
            pendingRequestId = null

            if (!GeoPosition.allowed(this)) {
                message("Permission de localisation refusée.")
                return@registerForActivityResult
            }

            when (action) {
                GpsAction.CENTER -> centerOnCurrentPosition()
                GpsAction.SEND -> sendPosition(requestId)
                GpsAction.TRACK -> prepareTracking()
                GpsAction.NONE -> Unit
            }
        }

    private val notificationPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (!granted) {
                message(
                    "Notifications refusées. " +
                            "Le suivi doit rester visible dans les services Android."
                )
            }

            startTrackingService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        api = SmartGeoApi(applicationContext)

        // Configuration de la carte OpenStreetMap.
        val config = Configuration.getInstance()

        config.load(
            applicationContext,
            getSharedPreferences(
                "smartgeo_osm",
                MODE_PRIVATE
            )
        )

        config.userAgentValue = packageName

        setContentView(R.layout.activity_gps)

        mapView = findViewById(R.id.mapView)

        consentSwitch = findViewById(
            R.id.switchConsent
        )

        statusView = findViewById(
            R.id.tvGpsStatus
        )

        positionView = findViewById(
            R.id.tvLastPosition
        )

        schedulesView = findViewById(
            R.id.tvGpsSchedules
        )

        requestsContainer = findViewById(
            R.id.locationRequestsContainer
        )

        progress = findViewById(
            R.id.progressGps
        )

        trackingButton = findViewById(
            R.id.btnTracking
        )

        pointsCountView = findViewById(
            R.id.tvPointsCount
        )

        mapHintView = findViewById(
            R.id.tvMapHint
        )

        configureMap()

        // Conservation du systeme de notifications.
        setupNavigation(R.id.nav_gps) { categories ->

            if ("gps" in categories) {
                loadGpsData()
            }
        }

        consentSwitch.isEnabled = false

        findViewById<MaterialButton>(
            R.id.btnCenterMap
        ).setOnClickListener {
            requestGpsAction(GpsAction.CENTER)
        }

        findViewById<MaterialButton>(
            R.id.btnSendGps
        ).setOnClickListener {
            requestGpsAction(GpsAction.SEND)
        }

        trackingButton.setOnClickListener {
            if (isTracking()) {
                stopTracking()
                message("Suivi automatique arrêté.")
            } else {
                requestGpsAction(GpsAction.TRACK)
            }
        }

        findViewById<MaterialButton>(
            R.id.btnRefreshGps
        ).setOnClickListener {
            loadGpsData()
        }

        loadGpsData()
    }

    // ------------------------------------------------
    // CONFIGURATION CARTE
    // ------------------------------------------------

    private fun configureMap() {

        mapView.setTileSource(
            TileSourceFactory.MAPNIK
        )

        mapView.setMultiTouchControls(true)

        mapView.zoomController
            .setVisibility(
                org.osmdroid.views.CustomZoomButtonsController
                    .Visibility.NEVER
            )

        // Vue generale tant qu'aucun GPS n'est charge.
        mapView.controller.setZoom(3.0)

        mapView.controller.setCenter(
            GeoPoint(0.0, 0.0)
        )

        // Evite que le ScrollView bloque le zoom
        // et les deplacements tactiles dans la carte.
        mapView.setOnTouchListener { view, event ->

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    view.parent
                        .requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    view.parent
                        .requestDisallowInterceptTouchEvent(false)
                }
            }

            false
        }
    }

    // ------------------------------------------------
    // CHARGER LES DONNEES GPS
    // ------------------------------------------------

    private fun loadGpsData() {

        lifecycleScope.launch {

            progress.visibility = View.VISIBLE

            try {
                loadConsentAndStatus()
            } catch (error: Exception) {
                statusView.text =
                    error.message ?: "Statut GPS indisponible."
            }

            try {
                loadSchedules()
            } catch (error: Exception) {
                schedulesView.text =
                    error.message ?: "Plannings indisponibles."
            }

            try {
                loadHistoryAndMap()
            } catch (error: Exception) {
                mapHintView.text =
                    error.message ?: "Carte indisponible."
            }

            try {
                loadRequests()
            } catch (error: Exception) {
                requestsContainer.removeAllViews()

                requestsContainer.addSmartCard(
                    "Demandes indisponibles",
                    error.message ?: "Chargement impossible."
                )
            }

            progress.visibility = View.GONE

            refreshTrackingButton()
        }
    }

    private suspend fun loadConsentAndStatus() {

        val consent = api.getObject(
            "/gps-consent/me"
        )

        consentEnabled = consent.optBoolean("enabled")

        consentSwitch.setOnCheckedChangeListener(null)
        consentSwitch.isChecked = consentEnabled
        consentSwitch.isEnabled = true

        consentSwitch.setOnCheckedChangeListener {
                _, enabled ->
            updateConsent(enabled)
        }

        val status = api.getObject(
            "/gps/status"
        )

        statusView.text = if (
            status.optBoolean("allowed")
        ) {
            "GPS autorisé pendant le planning actif."
        } else {
            status.field("message")
                .ifBlank {
                    "GPS non autorisé actuellement."
                }
        }
    }

    private suspend fun loadSchedules() {

        val schedules = api.getArray(
            "/schedules/me"
        ).objects()

        schedulesView.text = if (
            schedules.isEmpty()
        ) {
            "Aucun planning configuré par l'ADMIN."
        } else {
            schedules.joinToString("\n\n") { schedule ->

                val start = schedule.field(
                    "start_time"
                ).ifBlank { "—" }

                val end = schedule.field(
                    "end_time"
                ).ifBlank { "—" }

                val frequency = schedule.optInt(
                    "frequency_seconds",
                    30
                )

                val active = schedule.optBoolean(
                    "enabled"
                )

                val state = if (active) {
                    "Activé"
                } else {
                    "Désactivé"
                }

                "$start - $end\n" +
                        "Intervalle : $frequency secondes\n" +
                        "État : $state"
            }
        }
    }

    // ------------------------------------------------
    // HISTORIQUE ET PARCOURS GPS
    // ------------------------------------------------

    private suspend fun loadHistoryAndMap() {
        if (captureInProgress) return
        val version = ++historyVersion
        val history = api.getArray("/positions/me/history?limit=150").objects()
        if (version != historyVersion || captureInProgress) return
        val points = history.asReversed().mapNotNull { p ->
            val lat = p.optDouble("latitude", Double.NaN)
            val lon = p.optDouble("longitude", Double.NaN)
            if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
                TrackPoint(GeoPoint(lat, lon), timestamp(p.opt("recorded_at")), p)
            else null
        }
        var start = 0
        for (i in 1 until points.size) {
            if (!continuous(points[i - 1], points[i])) start = i
        }
        val recent = points.drop(start)
        drawHistory(recent.map { it.point })
        pointsCountView.text = "${points.size} positions enregistrées · ${recent.size} dans le trajet récent"
        val source = if (GeoPosition.isEmulator()) "GPS simulé de l'émulateur. " else ""
        mapHintView.text = source + if (start > 0)
            "Trajet récent uniquement : les ruptures de temps ou de distance séparent les anciens trajets."
        else "Le trait bleu relie les positions du trajet récent."
        val latest = recent.lastOrNull()
        positionView.text = if (latest == null) "Aucune position GPS enregistrée."
        else "Latitude : ${coordinate(latest.point.latitude)}\n" +
            "Longitude : ${coordinate(latest.point.longitude)}\n" +
            "Précision annoncée : ${latest.raw.field("accuracy").ifBlank { "—" }} m\n" +
            "Dernier relevé : ${latest.date?.toString() ?: "date inconnue"}"
    }

    private fun drawHistory(points: List<GeoPoint>) {

        mapView.overlays.clear()
        currentMarker = null

        if (points.isEmpty()) {
            mapView.invalidate()
            return
        }

        // Trait bleu epais visible sur la carte.
        if (points.size >= 2) {

            val path = Polyline(mapView).apply {

                setPoints(points)

                outlinePaint.color =
                    ContextCompat.getColor(
                        this@GpsActivity,
                        R.color.sg_primary
                    )

                outlinePaint.strokeWidth =
                    dp(7).toFloat()

                outlinePaint.isAntiAlias = true
            }

            mapView.overlays.add(path)
        }

        if (points.size > 1) {
            val startMarker = Marker(mapView).apply {
                position = points.first()
                title = "Début du parcours"

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_BOTTOM
                )
            }

            mapView.overlays.add(startMarker)
        }

        val endMarker = Marker(mapView).apply {
            position = points.last()
            title = "Dernière position enregistrée"

            setAnchor(
                Marker.ANCHOR_CENTER,
                Marker.ANCHOR_BOTTOM
            )
        }

        mapView.overlays.add(endMarker)

        // Positionnement automatique de la carte.
        mapView.post {

            if (points.distinct().size >= 2) {

                val bounds = BoundingBox.fromGeoPoints(
                    points
                )

                mapView.zoomToBoundingBox(
                    bounds,
                    true,
                    dp(45)
                )

            } else {

                mapView.controller.setZoom(16.0)

                mapView.controller.animateTo(
                    points.last()
                )
            }

            mapView.invalidate()
        }
    }

    private fun coordinate(value: Double): String =
        String.format(
            Locale.US,
            "%.6f",
            value
        )

    // ------------------------------------------------
    // MA POSITION ACTUELLE
    // ------------------------------------------------

    private fun requestGpsAction(
        action: GpsAction,
        requestId: Long? = null
    ) {

        if (!GeoPosition.allowed(this)) {

            pendingAction = action
            pendingRequestId = requestId

            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )

            return
        }

        when (action) {
            GpsAction.CENTER -> centerOnCurrentPosition()
            GpsAction.SEND -> sendPosition(requestId)
            GpsAction.TRACK -> prepareTracking()
            GpsAction.NONE -> Unit
        }
    }

    private fun centerOnCurrentPosition() {
        if (captureInProgress) return
        captureInProgress = true
        historyVersion++
        lifecycleScope.launch {

            progress.visibility = View.VISIBLE

            try {
                val position = GeoPosition.capture(
                    applicationContext
                )

                val point = GeoPoint(
                    position.getDouble("latitude"),
                    position.getDouble("longitude")
                )

                currentMarker?.let {
                    mapView.overlays.remove(it)
                }

                val marker = Marker(mapView).apply {
                    this.position = point
                    title = "Ma position actuelle"

                    setAnchor(
                        Marker.ANCHOR_CENTER,
                        Marker.ANCHOR_BOTTOM
                    )
                }

                currentMarker = marker

                mapView.overlays.add(marker)

                mapView.controller.setZoom(17.0)
                mapView.controller.animateTo(point)
                mapView.invalidate()

                val source = if (GeoPosition.isEmulator()) "GPS simulé" else "GPS de l'appareil"
                mapHintView.text = "$source · précision annoncée : ${position.optDouble("accuracy")} m"
                message("Position actuelle affichée ($source).")

            } catch (error: Exception) {
                message(
                    error.message
                        ?: "Position actuelle indisponible."
                )
            } finally {
                captureInProgress = false
                progress.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------
    // ENVOI GPS AU BACKEND
    // ------------------------------------------------

    private fun sendPosition(requestId: Long?) {

        if (captureInProgress) return
        captureInProgress = true
        historyVersion++
        lifecycleScope.launch {

            progress.visibility = View.VISIBLE

            try {
                val status = api.getObject(
                    "/gps/status"
                )

                if (!status.optBoolean("allowed")) {
                    error(
                        status.field("message")
                            .ifBlank {
                                "Transmission refusée par le serveur."
                            }
                    )
                }

                val position = GeoPosition.capture(
                    applicationContext
                )

                val response = api.post(
                    "/positions",
                    position
                )

                val data = response.optJSONObject(
                    "data"
                ) ?: response

                val positionId = data.optLong("id")

                if (requestId != null) {

                    if (positionId <= 0) {
                        error(
                            "La position n'a pas d'identifiant valide."
                        )
                    }

                    api.put(
                        "/location-requests/$requestId/respond",
                        JSONObject()
                            .put(
                                "status",
                                "TRANSMITTED"
                            )
                            .put(
                                "positionId",
                                positionId
                            )
                    )
                }

                message(
                    "Position GPS transmise avec succès."
                )

                captureInProgress = false
                loadGpsData()

            } catch (error: Exception) {
                message(
                    error.message
                        ?: "Impossible de transmettre la position."
                )
            } finally {
                captureInProgress = false
                progress.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------
    // CONSENTEMENT GPS
    // ------------------------------------------------

    private fun updateConsent(enabled: Boolean) {

        lifecycleScope.launch {

            progress.visibility = View.VISIBLE

            try {
                api.put(
                    "/gps-consent/me",
                    JSONObject().put(
                        "enabled",
                        enabled
                    )
                )

                consentEnabled = enabled

                if (!enabled) {
                    stopTracking()
                }

                message(
                    if (enabled) {
                        "Partage GPS autorisé."
                    } else {
                        "Partage GPS désactivé."
                    }
                )

            } catch (error: Exception) {

                consentSwitch.setOnCheckedChangeListener(null)
                consentSwitch.isChecked = consentEnabled

                consentSwitch.setOnCheckedChangeListener {
                        _, checked ->
                    updateConsent(checked)
                }

                message(
                    error.message
                        ?: "Modification du consentement impossible."
                )
            } finally {
                progress.visibility = View.GONE
                loadGpsData()
            }
        }
    }

    // ------------------------------------------------
    // DEMANDES DE LOCALISATION
    // ------------------------------------------------

    private suspend fun loadRequests() {

        val requests = api.getArray(
            "/location-requests"
        ).objects()

        requestsContainer.removeAllViews()

        if (requests.isEmpty()) {
            requestsContainer.addSmartCard(
                "Aucune demande",
                "Vous n'avez aucune demande de localisation."
            )
            return
        }

        requests.take(15).forEach { request ->

            val id = request.optLong("id")

            val state = request.field(
                "status"
            )

            val admin = request.field(
                "admin_name"
            ).ifBlank { "ADMIN" }

            requestsContainer.addSmartCard(
                title = "Demande #$id",
                description =
                    "Administrateur : $admin",
                status = "Statut : $state",
                buttonText = if (state == "PENDING") {
                    "Répondre à la demande"
                } else {
                    null
                },
                onButton = if (state == "PENDING") {
                    {
                        showRequestDialog(id)
                    }
                } else {
                    null
                }
            )
        }
    }

    private fun showRequestDialog(requestId: Long) {

        AlertDialog.Builder(this)
            .setTitle("Demande de localisation")
            .setMessage(
                "Voulez-vous transmettre votre position " +
                        "à l'administrateur ?"
            )
            .setPositiveButton(
                "Transmettre"
            ) { _, _ ->

                requestGpsAction(
                    GpsAction.SEND,
                    requestId
                )
            }
            .setNegativeButton(
                "Refuser"
            ) { _, _ ->

                rejectRequest(requestId)
            }
            .setNeutralButton(
                "Annuler",
                null
            )
            .show()
    }

    private fun rejectRequest(requestId: Long) {

        lifecycleScope.launch {

            try {
                api.put(
                    "/location-requests/$requestId/respond",
                    JSONObject().put(
                        "status",
                        "REFUSED"
                    )
                )

                message("Demande refusée.")
                loadGpsData()

            } catch (error: Exception) {
                message(
                    error.message
                        ?: "Réponse non enregistrée."
                )
            }
        }
    }

    // ------------------------------------------------
    // SUIVI GPS AUTOMATIQUE
    // ------------------------------------------------

    private fun prepareTracking() {

        if (!consentEnabled) {
            message(
                "Activez d'abord le partage GPS."
            )
            return
        }

        lifecycleScope.launch {

            try {
                val status = api.getObject(
                    "/gps/status"
                )

                if (!status.optBoolean("allowed")) {
                    message(
                        status.field("message")
                            .ifBlank {
                                "Suivi GPS non autorisé actuellement."
                            }
                    )
                    return@launch
                }

                if (
                    Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@GpsActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                } else {
                    startTrackingService()
                }

            } catch (error: Exception) {
                message(
                    error.message
                        ?: "Impossible de vérifier le suivi GPS."
                )
            }
        }
    }

    private fun startTrackingService() {

        try {
            val intent = Intent(
                this,
                TrackingService::class.java
            ).setAction(
                TrackingService.ACTION_START
            )

            ContextCompat.startForegroundService(
                this,
                intent
            )

            message(
                "Suivi automatique demandé."
            )

            refreshTrackingButton()

        } catch (error: Exception) {
            message(
                error.message
                    ?: "Démarrage du service GPS impossible."
            )
        }
    }

    private fun stopTracking() {

        stopService(
            Intent(
                this,
                TrackingService::class.java
            )
        )

        getSharedPreferences(
            TrackingService.PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                TrackingService.KEY_ACTIVE,
                false
            )
            .apply()

        refreshTrackingButton()
    }

    private fun isTracking(): Boolean {

        return getSharedPreferences(
            TrackingService.PREFS,
            MODE_PRIVATE
        ).getBoolean(
            TrackingService.KEY_ACTIVE,
            false
        )
    }

    private fun refreshTrackingButton() {

        trackingButton.text = if (
            isTracking()
        ) {
            "Arrêter le suivi automatique"
        } else {
            "Démarrer le suivi automatique"
        }
    }

    // ------------------------------------------------
    // CYCLE DE VIE DE LA CARTE
    // ------------------------------------------------

    override fun onResume() {
        super.onResume()

        if (::mapView.isInitialized) {
            mapView.onResume()
        }

        if (::trackingButton.isInitialized) {
            refreshTrackingButton()
            historyRefresh?.cancel()
            historyRefresh = lifecycleScope.launch {
                while (isActive) {
                    delay(15_000L)
                    if (isTracking() && !captureInProgress) {
                        try { loadHistoryAndMap() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { /* prochain rafraîchissement */ }
                    }
                    refreshTrackingButton()
                }
            }
        }
    }

    override fun onPause() {
        historyRefresh?.cancel()
        historyRefresh = null

        if (::mapView.isInitialized) {
            mapView.onPause()
        }

        super.onPause()
    }

    override fun onDestroy() {

        if (::mapView.isInitialized) {
            mapView.onDetach()
        }

        super.onDestroy()
    }
}
