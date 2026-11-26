package com.smartgeo.android

import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Recuperation automatique de l'adresse
 * du backend SmartGeo depuis GitHub.
 */
object SmartGeoServer {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/oa6-boop/Smartgeo/main/server.json"

    private const val CACHE_DURATION_MS = 30_000L

    private var cachedUrl: String? = null

    private var cachedAt: Long = 0L

    private val allowedAddress = Regex(
        """https://[a-zA-Z0-9-]+\.trycloudflare\.com/smartgeo/api"""
    )

    /**
     * Recuperer l'adresse actuelle du serveur.
     *
     * Cette methode est executee depuis
     * Dispatchers.IO dans SmartGeoApi.
     */
    @Synchronized
    @Throws(IOException::class)
    fun apiBaseUrl(
        forceRefresh: Boolean = false
    ): String {

        val now = SystemClock.elapsedRealtime()

        val existing = cachedUrl

        // Reutiliser temporairement l'adresse.

        if (
            !forceRefresh &&
            existing != null &&
            now - cachedAt < CACHE_DURATION_MS
        ) {

            return existing
        }

        // Ne pas reutiliser une adresse expiree
        // si la recuperation GitHub echoue.

        cachedUrl = null
        cachedAt = 0L

        // Limiter les problemes de cache GitHub.

        val configAddress =
            "$CONFIG_URL?v=${System.currentTimeMillis()}"

        val connection = URL(configAddress)
            .openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "GET"

            connection.connectTimeout = 10000

            connection.readTimeout = 10000

            connection.useCaches = false

            connection.instanceFollowRedirects = false

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Cache-Control",
                "no-cache"
            )

            val httpCode = connection.responseCode

            if (httpCode != 200) {

                throw IOException(
                    "Configuration SmartGeo indisponible " +
                    "(HTTP $httpCode)."
                )
            }

            val content = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use {
                    it.readText()
                }

            val configuration = JSONObject(content)

            val address = configuration
                .optString("apiUrl")
                .trim()
                .trimEnd('/')

            // Autoriser uniquement les adresses HTTPS
            // Cloudflare correspondant a SmartGeo.

            if (!allowedAddress.matches(address)) {

                throw IOException(
                    "Adresse du serveur SmartGeo invalide."
                )
            }

            cachedUrl = address

            cachedAt = SystemClock.elapsedRealtime()

            return address

        } catch (error: IOException) {

            throw error

        } catch (error: Exception) {

            throw IOException(
                "Impossible de recuperer le serveur SmartGeo.",
                error
            )

        } finally {

            connection.disconnect()
        }
    }

    /**
     * Forcer la recuperation de la nouvelle adresse
     * lors de la prochaine requete.
     */
    @Synchronized
    fun invalidate() {

        cachedUrl = null

        cachedAt = 0L
    }
}