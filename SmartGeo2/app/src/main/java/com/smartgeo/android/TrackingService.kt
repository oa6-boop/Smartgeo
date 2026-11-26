
package com.smartgeo.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TrackingService : Service() {

    companion object {

        const val ACTION_START =
            "com.smartgeo.android.START_TRACKING"

        const val ACTION_STOP =
            "com.smartgeo.android.STOP_TRACKING"

        const val PREFS = "smartgeo_tracking"

        const val KEY_ACTIVE = "tracking_active"

        private const val CHANNEL_ID =
            "smartgeo_gps_service"

        private const val NOTIFICATION_ID = 1001

        private const val TAG = "SmartGeoTracking"
    }

    private val serviceJob = SupervisorJob()

    private val scope = CoroutineScope(
        serviceJob + Dispatchers.IO
    )

    private var trackingJob: Job? = null

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {

            stopSelf()

            return START_NOT_STICKY
        }

        val api = SmartGeoApi(
            applicationContext
        )

        if (
            !api.hasSession() ||
            !GeoPosition.allowed(this)
        ) {

            Log.w(
                TAG,
                "Demarrage refuse : session absente " +
                        "ou permission GPS precise manquante."
            )

            stopSelf()

            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            notification(
                "Verification des autorisations GPS..."
            )
        )

        setActive(true)

        if (trackingJob?.isActive != true) {

            trackingJob = scope.launch {

                performTracking(api)
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Suivi automatique.
     *
     * Le serveur gere :
     * - les comptes ;
     * - le consentement ;
     * - les horaires ;
     * - les frequences d'envoi.
     *
     * Android gere :
     * - l'acquisition GPS ;
     * - les permissions ;
     * - le service visible ;
     * - l'envoi des coordonnees.
     */
    private suspend fun performTracking(
        api: SmartGeoApi
    ) {

        while (
            currentCoroutineContext().isActive
        ) {

            if (
                !api.hasSession() ||
                !GeoPosition.allowed(this)
            ) {

                Log.w(
                    TAG,
                    "Suivi interrompu : session ou " +
                            "permission GPS manquante."
                )

                stopSelf()
                break
            }

            var nextDelay = 60_000L

            try {

                // 1. Consentement de l'utilisateur.
                val consent = api.getObject(
                    "/gps-consent/me"
                )

                if (
                    !consent.optBoolean("enabled")
                ) {

                    Log.i(
                        TAG,
                        "Partage GPS desactive."
                    )

                    stopSelf()
                    break
                }

                // 2. Horaires autorises.
                val status = api.getObject(
                    "/gps/status"
                )

                if (
                    !status.optBoolean("allowed")
                ) {

                    updateNotification(
                        "Suivi en attente du planning GPS"
                    )

                } else {

                    // 3. Frequence active du serveur.
                    val schedules = api.getArray(
                        "/schedules/me"
                    ).objects()

                    val frequencySeconds = schedules
                        .filter {
                            it.optBoolean("enabled")
                        }
                        .map {
                            it.optInt(
                                "frequency_seconds",
                                30
                            )
                        }
                        .filter {
                            it > 0
                        }
                        .minOrNull() ?: 30

                    nextDelay = frequencySeconds
                        .coerceIn(15, 600)
                        .toLong() * 1_000L

                    // 4. Nouvelle acquisition GPS.
                    updateNotification(
                        "Recherche d'une position GPS precise..."
                    )

                    val position = try {

                        GeoPosition.capture(
                            applicationContext
                        )

                    } catch (
                        error: CancellationException
                    ) {

                        throw error

                    } catch (error: Exception) {

                        // Aucune position incorrecte
                        // n'est transmise dans ce cas.
                        Log.w(
                            TAG,
                            "Releve GPS refuse : " +
                                    error.message,
                            error
                        )

                        updateNotification(
                            "GPS indisponible ou imprecis"
                        )

                        null
                    }

                    // 5. Envoi uniquement si valide.
                    if (position != null) {

                        try {

                            api.post(
                                "/positions",
                                position
                            )

                            Log.i(
                                TAG,
                                "Position GPS transmise au serveur."
                            )

                            updateNotification(
                                if (GeoPosition.isEmulator()) "Suivi GPS simulé actif" else "Suivi GPS actif"
                            )

                            // SmartGeoApi.post programme déjà la synchronisation.

                        } catch (
                            error: CancellationException
                        ) {

                            throw error

                        } catch (error: Exception) {

                            Log.w(
                                TAG,
                                "Envoi de position impossible.",
                                error
                            )

                            val message =
                                error.message.orEmpty()

                            if (
                                "HTTP 401" in message ||
                                "HTTP 403" in message
                            ) {

                                updateNotification(
                                    "Suivi refuse par le serveur"
                                )

                                stopSelf()
                                break

                            } else {

                                updateNotification(
                                    "Position capturee, " +
                                            "transmission non confirmee"
                                )
                            }
                        }
                    }
                }

            } catch (
                error: CancellationException
            ) {

                throw error

            } catch (error: Exception) {

                Log.w(
                    TAG,
                    "Verification du suivi impossible.",
                    error
                )

                val message =
                    error.message.orEmpty()

                if (
                    "HTTP 401" in message ||
                    "HTTP 403" in message
                ) {

                    updateNotification(
                        "Suivi arrete : acces refuse"
                    )

                    stopSelf()
                    break
                }

                updateNotification(
                    "Connexion au serveur indisponible"
                )
            }

            delay(nextDelay)
        }
    }

    /**
     * Canal Android pour la notification
     * persistante de geolocalisation.
     */
    private fun createNotificationChannel() {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Suivi GPS SmartGeo",
            NotificationManager.IMPORTANCE_LOW
        ).apply {

            description =
                "Suivi GPS autorise par l'utilisateur"
        }

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)
    }

    private fun notification(
        status: String
    ): Notification {

        val openIntent = Intent(
            this,
            GpsActivity::class.java
        )

        val openPending = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(
            this,
            TrackingService::class.java
        ).setAction(
            ACTION_STOP
        )

        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_menu_mylocation
            )
            .setContentTitle(
                "SmartGeo - Suivi GPS"
            )
            .setContentText(status)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Arreter",
                stopPending
            )
            .build()
    }

    private fun updateNotification(
        message: String
    ) {

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            notification(message)
        )
    }

    private fun setActive(
        active: Boolean
    ) {

        getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_ACTIVE,
                active
            )
            .apply()
    }

    override fun onDestroy() {

        setActive(false)

        trackingJob?.cancel()

        scope.cancel()

        super.onDestroy()
    }
}
