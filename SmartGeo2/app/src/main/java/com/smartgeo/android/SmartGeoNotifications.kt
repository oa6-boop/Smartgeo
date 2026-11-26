
package com.smartgeo.android

import android.content.Context
import org.json.JSONObject

object SmartGeoNotifications {

    data class Alert(
        val category: String,
        val message: String
    )

    private const val PREFS = "smartgeo_notification_state"

    private fun key(
        userId: Long,
        category: String,
        suffix: String
    ): String = "user_${userId}_${category}_$suffix"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    fun unreadCount(
        context: Context,
        userId: Long,
        category: String
    ): Int {
        if (userId <= 0L) return 0

        return preferences(context)
            .getStringSet(
                key(userId, category, "unread"),
                emptySet()
            )
            .orEmpty()
            .size
    }

    fun markRead(
        context: Context,
        userId: Long,
        category: String
    ) {
        if (userId <= 0L) return

        preferences(context)
            .edit()
            .remove(key(userId, category, "unread"))
            .apply()
    }

    suspend fun check(
        context: Context,
        api: SmartGeoApi
    ): List<Alert> {

        val userId = api.userId()

        if (userId <= 0 || !api.hasSession()) {
            return emptyList()
        }

        val alerts = mutableListOf<Alert>()

        // 1. Nouvelles missions affectees.
        try {
            val missions = api.getArray(
                "/missions"
            ).objects()

            val ids = missions.mapNotNull {
                positiveId(it)
            }.toSet()

            saveChanges(
                context,
                userId,
                "missions",
                ids
            )?.let { count ->

                alerts.add(
                    Alert(
                        "missions",
                        if (count == 1) {
                            "Une nouvelle mission vous a été attribuée."
                        } else {
                            "$count nouvelles missions vous ont été attribuées."
                        }
                    )
                )
            }
        } catch (_: Exception) {
            // On conserve les autres notifications.
        }

        // 2. Messages recus uniquement.
        try {
            val inbox = api.getArray(
                "/messages/inbox"
            ).objects()

            val ids = inbox
                .filter {
                    it.optLong("sender_id") != userId
                }
                .mapNotNull {
                    positiveId(it)
                }
                .toSet()

            saveChanges(
                context,
                userId,
                "messages",
                ids
            )?.let { count ->

                alerts.add(
                    Alert(
                        "messages",
                        if (count == 1) {
                            "Vous avez reçu un nouveau message."
                        } else {
                            "Vous avez reçu $count nouveaux messages."
                        }
                    )
                )
            }
        } catch (_: Exception) {
        }

        // 3. Demandes GPS de l'administrateur.
        try {
            val requests = api.getArray(
                "/location-requests"
            ).objects()

            val ids = requests
                .filter {
                    it.optString("status") == "PENDING"
                }
                .mapNotNull {
                    positiveId(it)
                }
                .toSet()

            saveChanges(
                context,
                userId,
                "gps",
                ids
            )?.let { count ->

                alerts.add(
                    Alert(
                        "gps",
                        if (count == 1) {
                            "Nouvelle demande de localisation GPS."
                        } else {
                            "$count nouvelles demandes de localisation."
                        }
                    )
                )
            }
        } catch (_: Exception) {
        }

        // 4. Signalements examines par l'ADMIN.
        try {
            val reports = api.getArray(
                "/reports"
            ).objects()

            val states = reports
                .filter {
                    it.optString("status") in setOf(
                        "REVIEWED",
                        "REJECTED"
                    )
                }
                .mapNotNull { report ->
                    val id = positiveId(report)

                    if (id == null) {
                        null
                    } else {
                        "$id:${report.optString("status")}"
                    }
                }
                .toSet()

            saveChanges(
                context,
                userId,
                "reports",
                states
            )?.let { count ->

                alerts.add(
                    Alert(
                        "reports",
                        if (count == 1) {
                            "Le statut d'un signalement a changé."
                        } else {
                            "Le statut de $count signalements a changé."
                        }
                    )
                )
            }
        } catch (_: Exception) {
        }

        return alerts
    }

    private fun positiveId(item: JSONObject): String? {
        val id = item.optLong("id", -1L)

        return if (id > 0L) {
            id.toString()
        } else {
            null
        }
    }

    /**
     * Retourne null si aucun nouvel evenement
     * n'a ete detecte.
     *
     * Lors de la premiere consultation, on memorise
     * l'etat existant sans afficher de fausse alerte.
     */
    private fun saveChanges(
        context: Context,
        userId: Long,
        category: String,
        current: Set<String>
    ): Int? {

        val prefs = preferences(context)

        val initializedKey = key(
            userId,
            category,
            "initialized"
        )

        val seenKey = key(
            userId,
            category,
            "seen"
        )

        val unreadKey = key(
            userId,
            category,
            "unread"
        )

        if (!prefs.getBoolean(initializedKey, false)) {

            prefs.edit()
                .putBoolean(initializedKey, true)
                .putStringSet(seenKey, current.toSet())
                .apply()

            return null
        }

        val seen = prefs
            .getStringSet(seenKey, emptySet())
            .orEmpty()
            .toSet()

        val newItems = current - seen

        if (newItems.isEmpty()) {
            return null
        }

        val unread = prefs
            .getStringSet(unreadKey, emptySet())
            .orEmpty()
            .toMutableSet()

        unread.addAll(newItems)

        prefs.edit()
            .putStringSet(
                seenKey,
                (seen + newItems).toSet()
            )
            .putStringSet(
                unreadKey,
                unread.toSet()
            )
            .apply()

        return newItems.size
    }
}
