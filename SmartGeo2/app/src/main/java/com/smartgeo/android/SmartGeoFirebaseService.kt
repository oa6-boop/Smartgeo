package com.smartgeo.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SmartGeoFirebaseService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID =
            "smartgeo_notifications"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        // Enregistrer le nouveau token si le USER
        // dispose deja d'une session valide.
        SmartGeoPush.registerToken(
            applicationContext,
            token
        )
    }

    override fun onMessageReceived(
        message: RemoteMessage
    ) {
        super.onMessageReceived(message)

        val title = message.data["title"]
            ?: "SmartGeo"

        val body = message.data["body"]
            ?: "Vous avez une nouvelle notification."

        val type = message.data["type"]
            ?: "general"

        showNotification(
            title,
            body,
            type
        )
    }

    private fun showNotification(
        title: String,
        body: String,
        type: String
    ) {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = getSystemService(
            NotificationManager::class.java
        )

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alertes SmartGeo",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        channel.description =
            "Missions, messages et demandes de localisation"

        manager.createNotificationChannel(channel)

        val destination = when (type) {

            "mission" ->
                MissionsActivity::class.java

            "message" ->
                MessagesActivity::class.java

            "gps" ->
                GpsActivity::class.java

            "report" ->
                ReportsActivity::class.java

            else ->
                MainActivity::class.java
        }

        val intent = Intent(
            this,
            destination
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val notificationId =
            (System.currentTimeMillis() and 0x7FFFFFFF)
                .toInt()

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_dialog_info
            )
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(
                NotificationCompat.PRIORITY_DEFAULT
            )
            .build()

        manager.notify(
            notificationId,
            notification
        )
    }
}