package com.smartgeo.android

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.json.JSONObject

class MissionsActivity : AppCompatActivity() {

    private lateinit var api: SmartGeoApi
    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_missions)

        api = SmartGeoApi(applicationContext)

        setupNavigation(R.id.nav_missions)

        container = findViewById(R.id.missionContainer)
        emptyView = findViewById(R.id.tvMissionsEmpty)
        progress = findViewById(R.id.progressMissions)

        findViewById<MaterialButton>(
            R.id.btnRefreshMissions
        ).setOnClickListener {
            loadMissions()
        }

        loadMissions()
    }

    private fun loadMissions() {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            container.removeAllViews()

            try {
                val missions = api.getArray(
                    "/missions"
                ).objects()

                emptyView.visibility = if (
                    missions.isEmpty()
                ) View.VISIBLE else View.GONE

                emptyView.text = "Aucune mission affectée."

                missions.forEach { mission ->
                    val id = mission.optLong("id")
                    val status = mission.optString("status")

                    val title = mission.field("title")
                        .ifBlank { "Mission #$id" }

                    val description = buildString {
                        append(
                            mission.field("description")
                                .ifBlank { "Aucune description." }
                        )

                        append("\n\nDébut : ")
                        append(
                            mission.field("start_date")
                                .ifBlank { "Non défini" }
                        )

                        append("\nFin : ")
                        append(
                            mission.field("end_date")
                                .ifBlank { "Non défini" }
                        )
                    }

                    val nextStatus = when (status) {
                        "PENDING" -> "IN_PROGRESS"
                        "IN_PROGRESS" -> "COMPLETED"
                        else -> null
                    }

                    val actionLabel = when (nextStatus) {
                        "IN_PROGRESS" -> "Commencer la mission"
                        "COMPLETED" -> "Terminer la mission"
                        else -> null
                    }

                    container.addSmartCard(
                        title = title,
                        description = description,
                        status = "Statut : $status",
                        buttonText = actionLabel,
                        onButton = if (nextStatus != null) {
                            {
                                updateStatus(id, nextStatus)
                            }
                        } else {
                            null
                        }
                    )
                }
            } catch (e: Exception) {
                emptyView.text =
                    e.message ?: "Chargement impossible."

                emptyView.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun updateStatus(
        missionId: Long,
        status: String
    ) {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE

            try {
                api.put(
                    "/missions/$missionId/status",
                    JSONObject().put("status", status)
                )

                message("Statut de mission enregistré.")
                loadMissions()
            } catch (e: Exception) {
                message(
                    e.message ?: "Modification impossible."
                )
            } finally {
                progress.visibility = View.GONE
            }
        }
    }
}