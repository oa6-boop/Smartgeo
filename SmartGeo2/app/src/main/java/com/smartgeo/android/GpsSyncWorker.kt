package com.smartgeo.android

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class GpsSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(
    context,
    workerParams
) {

    override suspend fun doWork(): Result {

        val api = SmartGeoApi(applicationContext)

        if (!api.hasSession()) {
            return Result.success()
        }

        val userId = api.userId()

        if (userId <= 0) {
            return Result.success()
        }

        val queue = GpsQueue(applicationContext)

        if (queue.count(userId) == 0) {
            return Result.success()
        }

        try {
            // Verification du consentement au moment
            // de la synchronisation.
            val consent = api.getObject(
                "/gps-consent/me"
            )

            if (!consent.optBoolean("enabled")) {

                queue.clearUser(userId)

                Log.i(
                    "GpsSyncWorker",
                    "Positions locales effacees : consentement desactive."
                )

                return Result.success()
            }

            // Ne pas transmettre de positions hors
            // des horaires autorises par WildFly.
            val gpsStatus = api.getObject(
                "/gps/status"
            )

            if (!gpsStatus.optBoolean("allowed")) {

                Log.i(
                    "GpsSyncWorker",
                    "Synchronisation differee : GPS non autorise."
                )

                return Result.success()
            }

            var sent = 0

            val pending = queue.pending(
                userId,
                50
            )

            for (position in pending) {

                // On appelle directement request() pour
                // ne pas rajouter une position deja presente
                // dans la file en cas d'erreur reseau.
                api.request(
                    "POST",
                    "/positions",
                    position.payload
                )

                // Effacer seulement apres reponse 2xx.
                queue.delete(
                    userId,
                    position.uuid
                )

                sent++
            }

            Log.i(
                "GpsSyncWorker",
                "$sent positions GPS synchronisees."
            )

            val remaining = queue.count(userId)

            return if (remaining > 0) {
                Result.retry()
            } else {
                Result.success()
            }

        } catch (error: Exception) {

            Log.w(
                "GpsSyncWorker",
                "Synchronisation GPS impossible",
                error
            )

            val description = error.message.orEmpty()

            // Une erreur d'authentification ou une
            // interdiction du serveur ne doit pas
            // provoquer des tentatives en boucle.
            if (
                "HTTP 401" in description ||
                "HTTP 403" in description ||
                "HTTP 400" in description
            ) {
                return Result.success()
            }

            // Erreur temporaire : WorkManager
            // reessaiera apres son delai de reprise.
            return Result.retry()
        }
    }
}

object GpsSyncScheduler {

    private const val PERIODIC_NAME =
        "smartgeo_gps_periodic_sync"

    private const val IMMEDIATE_NAME =
        "smartgeo_gps_immediate_sync"

    private fun constraints(): Constraints {

        return Constraints.Builder()
            .setRequiredNetworkType(
                NetworkType.CONNECTED
            )
            .build()
    }

    fun start(context: Context) {

        val request =
            PeriodicWorkRequestBuilder<GpsSyncWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints())
                .build()

        WorkManager.getInstance(
            context.applicationContext
        ).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        requestSync(context)
    }

    fun requestSync(context: Context) {

        val request =
            OneTimeWorkRequestBuilder<GpsSyncWorker>()
                .setConstraints(constraints())
                .build()

        WorkManager.getInstance(
            context.applicationContext
        ).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun stop(context: Context) {

        val manager = WorkManager.getInstance(
            context.applicationContext
        )

        manager.cancelUniqueWork(
            PERIODIC_NAME
        )

        manager.cancelUniqueWork(
            IMMEDIATE_NAME
        )
    }
}