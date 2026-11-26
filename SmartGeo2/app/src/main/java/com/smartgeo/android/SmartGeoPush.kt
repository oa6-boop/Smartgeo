package com.smartgeo.android

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

object SmartGeoPush {

    private const val TAG = "SmartGeoPush"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    fun register(context: Context) {

        val appContext = context.applicationContext

        if (!SmartGeoApi(appContext).hasSession()) {
            return
        }

        FirebaseMessaging.getInstance()
            .token
            .addOnSuccessListener { token ->
                registerToken(
                    appContext,
                    token
                )
            }
            .addOnFailureListener { error ->
                Log.w(
                    TAG,
                    "Impossible d'obtenir le token FCM",
                    error
                )
            }
    }

    fun registerToken(
        context: Context,
        token: String
    ) {

        if (token.isBlank()) return

        val appContext = context.applicationContext

        scope.launch {
            try {
                val api = SmartGeoApi(appContext)

                if (!api.hasSession()) {
                    return@launch
                }

                api.put(
                    "/push-tokens/me",
                    JSONObject().put(
                        "token",
                        token
                    )
                )

                Log.i(
                    TAG,
                    "Token Firebase enregistre"
                )

            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "Enregistrement FCM impossible",
                    error
                )
            }
        }
    }

    suspend fun unregister(context: Context) {

        val api = SmartGeoApi(
            context.applicationContext
        )

        if (!api.hasSession()) return

        val token = suspendCancellableCoroutine<String?> {
                continuation ->

            FirebaseMessaging.getInstance()
                .token
                .addOnCompleteListener { task ->

                    if (continuation.isActive) {
                        continuation.resume(
                            if (task.isSuccessful) {
                                task.result
                            } else {
                                null
                            }
                        )
                    }
                }
        }

        if (!token.isNullOrBlank()) {
            api.request(
                "DELETE",
                "/push-tokens/me",
                JSONObject().put(
                    "token",
                    token
                )
            )
        }
    }
}