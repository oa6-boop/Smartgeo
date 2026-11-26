
package com.smartgeo.android

import android.location.Location
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var api: SmartGeoApi

    private lateinit var missionCount: TextView
    private lateinit var progressCount: TextView
    private lateinit var gpsState: TextView
    private lateinit var serverState: TextView

    private lateinit var gpsButton: MaterialButton
    private lateinit var messagesButton: MaterialButton

    private var dashboardJob: Job? = null

    private data class Point(
        val latitude: Double,
        val longitude: Double,
        val time: Instant
    )

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        api = SmartGeoApi(applicationContext)

        if (!api.hasSession()) {

            openScreen(LoginActivity::class.java)

            finish()

            return
        }

        setContentView(R.layout.activity_main)

        setupNavigation(R.id.nav_home)

        findViewById<TextView>(
            R.id.tvWelcome
        ).text = "Bonjour, ${api.displayName()}"

        missionCount =
            findViewById(R.id.tvMissionCount)

        progressCount =
            findViewById(R.id.tvProgressCount)

        gpsState =
            findViewById(R.id.tvGpsState)

        serverState =
            findViewById(R.id.tvServerState)

        gpsButton =
            findViewById(R.id.btnQuickGps)

        messagesButton =
            findViewById(R.id.btnQuickMessages)

        findViewById<MaterialButton>(
            R.id.btnQuickMissions
        ).setOnClickListener {

            openScreen(MissionsActivity::class.java)
        }

        gpsButton.setOnClickListener {

            openScreen(GpsActivity::class.java)
        }

        messagesButton.setOnClickListener {

            openScreen(MessagesActivity::class.java)
        }

        findViewById<MaterialButton>(
            R.id.btnQuickReports
        ).setOnClickListener {

            openScreen(ReportsActivity::class.java)
        }
    }

    override fun onResume() {

        super.onResume()

        if (
            !::gpsState.isInitialized ||
            !api.hasSession()
        ) return

        dashboardJob?.cancel()

        dashboardJob = lifecycleScope.launch {

            while (isActive) {

                loadDashboard()

                delay(30_000L)
            }
        }
    }

    override fun onPause() {

        dashboardJob?.cancel()

        dashboardJob = null

        super.onPause()
    }

    private suspend fun loadDashboard() {

        val details = mutableListOf<String>()

        try {

            val missions = api.getArray(
                "/missions"
            ).objects()

            missionCount.text =
                missions.size.toString()

            progressCount.text =
                missions.count {

                    it.optString("status") ==
                            "IN_PROGRESS"

                }.toString()

            details.add(
                "Serveur SmartGeo accessible"
            )

        } catch (e: CancellationException) {

            throw e

        } catch (_: Exception) {

            missionCount.text = "—"

            progressCount.text = "—"

            details.add(
                "Données serveur indisponibles"
            )
        }

        try {

            val status = api.getObject(
                "/gps/status"
            )

            val serviceActive =
                getSharedPreferences(
                    TrackingService.PREFS,
                    MODE_PRIVATE
                ).getBoolean(
                    TrackingService.KEY_ACTIVE,
                    false
                )

            val allowed =
                status.optBoolean("allowed")

            gpsState.text = when {

                status.optString("status") ==
                        "CONSENT_DISABLED" ->
                    "Partage GPS désactivé"

                status.optString("status") ==
                        "OUTSIDE_SCHEDULE" ->
                    "En attente du planning"

                allowed && serviceActive ->
                    "Service de suivi actif"

                allowed ->
                    "GPS autorisé"

                else ->
                    "GPS non autorisé"
            }

            gpsButton.text = if (serviceActive) {

                "Consulter mon suivi GPS"

            } else {

                "Ma localisation GPS"
            }

        } catch (e: CancellationException) {

            throw e

        } catch (_: Exception) {

            gpsState.text =
                "État GPS indisponible"
        }

        // Nombre de messages non lus.

        try {

            val messages = api.getArray(
                "/messages/inbox"
            ).objects()

            val unread = messages.count {

                it.field("read_at").isBlank()
            }

            messagesButton.text = if (unread > 0) {

                "Messagerie · $unread non lu(s)"

            } else {

                "Contacter l’administrateur"
            }

        } catch (e: CancellationException) {

            throw e

        } catch (_: Exception) {

            messagesButton.text = "Messagerie"
        }

        // Informations de la file GPS locale.

        try {

            val pending = withContext(
                Dispatchers.IO
            ) {

                GpsQueue(applicationContext)
                    .count(api.userId())
            }

            details.add(

                if (pending > 0) {

                    "$pending position(s) en attente"

                } else {

                    "Aucune position en attente"
                }
            )

        } catch (e: CancellationException) {

            throw e

        } catch (_: Exception) {

            details.add(
                "État de synchronisation indisponible"
            )
        }

        // Calcul du parcours observé aujourd'hui.

        try {

            details.add(todayActivity())

        } catch (e: CancellationException) {

            throw e

        } catch (_: Exception) {

            details.add(
                "Statistiques GPS indisponibles"
            )
        }

        serverState.text =
            details.joinToString("\n\n")
    }

    private fun timeOf(value: Any?): Instant? {

        return try {

            when (value) {

                is JSONArray -> LocalDateTime.of(
                    value.getInt(0),
                    value.getInt(1),
                    value.getInt(2),
                    value.optInt(3),
                    value.optInt(4),
                    value.optInt(5),
                    value.optInt(6)
                ).toInstant(ZoneOffset.UTC)

                is String -> runCatching {

                    Instant.parse(value)

                }.getOrElse {

                    LocalDateTime.parse(
                        value.replace(' ', 'T')
                    ).toInstant(ZoneOffset.UTC)
                }

                else -> null
            }

        } catch (_: Exception) {

            null
        }
    }

    private suspend fun todayActivity(): String {

        val zone = ZoneId.systemDefault()

        val start = LocalDate.now(zone)
            .atStartOfDay(zone)
            .toInstant()

        val history = api.getArray(

            "/positions/me/history" +

                    "?from=$start" +

                    "&limit=2000"

        ).objects()

        val points = history.mapNotNull { row ->

            val lat = row.optDouble(
                "latitude",
                Double.NaN
            )

            val lon = row.optDouble(
                "longitude",
                Double.NaN
            )

            val time =
                timeOf(row.opt("recorded_at"))

            if (
                time == null ||
                !lat.isFinite() ||
                !lon.isFinite() ||
                lat !in -90.0..90.0 ||
                lon !in -180.0..180.0
            ) {

                null

            } else {

                Point(lat, lon, time)
            }

        }.sortedBy { it.time }

        if (points.isEmpty()) {

            return "Aucun relevé GPS aujourd’hui"
        }

        var distance = 0.0

        var seconds = 0.0

        for (i in 1 until points.size) {

            val a = points[i - 1]

            val b = points[i]

            val elapsed =
                (b.time.toEpochMilli() -
                        a.time.toEpochMilli()) / 1000.0

            if (
                elapsed < 0 ||
                elapsed > 900
            ) continue

            val result = FloatArray(1)

            Location.distanceBetween(
                a.latitude,
                a.longitude,
                b.latitude,
                b.longitude,
                result
            )

            val meters = result[0].toDouble()

            if (
                meters <=
                100 + elapsed * (300.0 / 3.6)
            ) {

                distance += meters

                seconds += elapsed
            }
        }

        val km = String.format(
            Locale.FRANCE,
            "%.2f",
            distance / 1000
        )

        val minutes = (seconds / 60).toLong()

        val partial = if (history.size >= 2000) {

            "\nHistorique du jour partiel."

        } else {

            ""
        }

        return "Aujourd’hui : $km km estimés\n" +

                "Temps observé : $minutes min\n" +

                "${points.size} relevés GPS" +

                partial
    }
}
