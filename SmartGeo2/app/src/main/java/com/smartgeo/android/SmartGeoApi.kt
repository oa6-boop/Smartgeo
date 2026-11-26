package com.smartgeo.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SmartGeoApi(context: Context) {

    // Adresse du serveur geree par SmartGeoServer.

    private val appContext =
        context.applicationContext

    private val preferences =
        appContext.getSharedPreferences(
            "smartgeo_session",
            Context.MODE_PRIVATE
        )

    fun hasSession(): Boolean {
        return !preferences.getString(
            "token",
            null
        ).isNullOrBlank()
    }

    fun userId(): Long {
        return preferences.getLong(
            "userId",
            -1L
        )
    }

    fun displayName(): String {
        return preferences.getString(
            "fullName",
            "Agent"
        ) ?: "Agent"
    }

    fun logout() {

        // Les positions deja capturees restent
        // chiffrees et associees a l'ancien USER.
        // Elles ne seront pas envoyees tant que
        // ce compte n'est pas reconnecte.
        GpsSyncScheduler.stop(appContext)

        preferences.edit()
            .clear()
            .apply()
    }

    suspend fun login(
        username: String,
        password: String
    ) {

        val body = JSONObject()
            .put(
                "username",
                username.trim()
            )
            .put(
                "password",
                password
            )

        val response = request(
            "POST",
            "/auth/login",
            body
        ) as JSONObject

        val data = response.optJSONObject(
            "data"
        ) ?: response

        val user = data.getJSONObject(
            "user"
        )

        val role = user.optString(
            "role"
        )

        if (role != "USER") {
            error(
                "Cette application est reservee aux agents USER."
            )
        }

        val token = data.optString(
            "token"
        ).ifBlank {
            data.optString("accessToken")
        }

        if (token.isBlank()) {
            error(
                "Le serveur n'a pas renvoye de token JWT."
            )
        }

        val id = user.optLong(
            "id",
            -1L
        )

        if (id <= 0) {
            error(
                "Identifiant utilisateur invalide."
            )
        }

        preferences.edit()
            .putString(
                "token",
                token
            )
            .putLong(
                "userId",
                id
            )
            .putString(
                "fullName",
                user.optString(
                    "fullName"
                ).ifBlank {
                    username
                }
            )
            .apply()

        // Activer la synchronisation des positions
        // de ce compte apres la connexion.
        GpsSyncScheduler.start(appContext)
    }

    suspend fun getObject(
        path: String
    ): JSONObject {

        val result = request(
            "GET",
            path
        ) as JSONObject

        return result.optJSONObject(
            "data"
        ) ?: result
    }

    suspend fun getArray(
        path: String
    ): JSONArray {

        val result = request(
            "GET",
            path
        )

        if (result is JSONArray) {
            return result
        }

        if (result is JSONObject) {
            val data = result.opt(
                "data"
            )

            if (data is JSONArray) {
                return data
            }

            if (data is JSONObject) {
                return data.optJSONArray("items")
                    ?: data.optJSONArray("results")
                    ?: JSONArray()
            }

            return result.optJSONArray("items")
                ?: result.optJSONArray("results")
                ?: JSONArray()
        }

        return JSONArray()
    }

    /**
     * Envoi POST.
     *
     * Pour /positions uniquement :
     * en cas d'erreur reseau, enregistrer la
     * position dans SQLite.
     */
    suspend fun post(
        path: String,
        body: JSONObject
    ): JSONObject {

        return try {

            val result = request(
                "POST",
                path,
                body
            ) as JSONObject

            // Verifier egalement les anciens relevés
            // locaux lorsque le reseau fonctionne.
            if (path == "/positions") {
                GpsSyncScheduler.requestSync(
                    appContext
                )
            }

            result

        } catch (networkError: IOException) {

            // Les missions, messages et rapports
            // gardent leur fonctionnement actuel.
            if (path != "/positions") {
                throw networkError
            }

            val currentUserId = userId()

            if (
                currentUserId <= 0 ||
                !hasSession()
            ) {
                throw networkError
            }

            // A ce stade, le GPS a ete capture
            // apres verification des permissions
            // et du statut GPS par GpsActivity
            // ou TrackingService.
            val pendingCount = withContext(
                Dispatchers.IO
            ) {

                val queue = GpsQueue(
                    appContext
                )

                queue.add(
                    currentUserId,
                    body
                )

                queue.count(
                    currentUserId
                )
            }

            // Demander une synchronisation des
            // que le reseau revient.
            GpsSyncScheduler.start(
                appContext
            )

            throw IllegalStateException(
                "Connexion interrompue : " +
                        "position GPS conservee localement. " +
                        "$pendingCount position(s) en attente.",
                networkError
            )
        }
    }

    suspend fun put(
        path: String,
        body: JSONObject
    ): JSONObject {

        val response = request(
            "PUT",
            path,
            body
        ) as JSONObject

        // Si le USER revoque explicitement
        // son consentement, effacer les positions
        // locales qui n'ont pas ete transmises.
        if (
            path == "/gps-consent/me" &&
            !body.optBoolean(
                "enabled",
                true
            )
        ) {

            withContext(Dispatchers.IO) {
                GpsQueue(
                    appContext
                ).clearUser(
                    userId()
                )
            }
        }

        return response
    }

    suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null
    ): Any = withContext(Dispatchers.IO) {

        val url = URL(
            SmartGeoServer.apiBaseUrl(forceRefresh = path == "/auth/login") + path
        )

        val connection = url
            .openConnection() as HttpURLConnection

        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method

            connection.connectTimeout = 10000
            connection.readTimeout = 20000

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val token = preferences.getString(
                "token",
                null
            )

            if (!token.isNullOrBlank()) {

                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
            }

            if (body != null) {

                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.outputStream.use { stream ->
                    stream.write(
                        body.toString()
                            .toByteArray(
                                Charsets.UTF_8
                            )
                    )
                }
            }

            val httpCode =
                connection.responseCode

            val stream = if (
                httpCode in 200..299
            ) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val content = stream
                ?.bufferedReader(
                    Charsets.UTF_8
                )
                ?.use {
                    it.readText()
                }
                .orEmpty()
                .trim()

            val result: Any = when {

                content.startsWith("[") ->
                    JSONArray(content)

                content.startsWith("{") ->
                    JSONObject(content)

                else ->
                    JSONObject().put(
                        "message",
                        content
                    )
            }

            if (httpCode !in 200..299) {

                val obj = result as? JSONObject

                val description = obj
                    ?.optString(
                        "message"
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: obj?.optString(
                        "error"
                    )?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "Erreur HTTP $httpCode"

                throw IllegalStateException(
                    "$description (HTTP $httpCode)"
                )
            }

            if (
                result is JSONObject &&
                !result.optBoolean(
                    "success",
                    true
                )
            ) {
                error(
                    result.optString(
                        "message",
                        "Operation refusee."
                    )
                )
            }

            result

        } catch (networkError: IOException) {
            SmartGeoServer.invalidate()
            throw networkError
        } finally {
            connection.disconnect()
        }
    }
}

fun JSONArray.objects(): List<JSONObject> {

    return (0 until length()).mapNotNull {
        optJSONObject(it)
    }
}

fun JSONObject.field(
    vararg names: String
): String {

    for (name in names) {

        val value = optString(
            name,
            ""
        )

        if (
            value.isNotBlank() &&
            value != "null"
        ) {
            return value
        }
    }

    return ""
}