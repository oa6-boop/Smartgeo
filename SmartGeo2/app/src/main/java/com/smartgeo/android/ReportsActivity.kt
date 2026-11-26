package com.smartgeo.android

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.json.JSONObject

class ReportsActivity : AppCompatActivity() {

    private lateinit var api: SmartGeoApi
    private lateinit var missionSpinner: Spinner
    private lateinit var titleInput: TextInputEditText
    private lateinit var descriptionInput: TextInputEditText
    private lateinit var reportsContainer: LinearLayout
    private lateinit var progress: ProgressBar

    private var missions = emptyList<JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)

        api = SmartGeoApi(applicationContext)

        setupNavigation(R.id.nav_home)

        missionSpinner = findViewById(R.id.spinnerReportMissions)
        titleInput = findViewById(R.id.inputReportTitle)
        descriptionInput = findViewById(R.id.inputReportDescription)
        reportsContainer = findViewById(R.id.reportsContainer)
        progress = findViewById(R.id.progressReports)

        findViewById<MaterialButton>(
            R.id.btnSendReport
        ).setOnClickListener {
            sendReport()
        }

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE

            try {
                missions = api.getArray(
                    "/missions"
                ).objects().filter {
                    it.optString("status") != "CANCELLED"
                }

                val labels = missions.map {
                    "#${it.optLong("id")} - ${it.field("title")}"
                }

                val adapter = ArrayAdapter(
                    this@ReportsActivity,
                    android.R.layout.simple_spinner_item,
                    labels
                )

                adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item
                )

                missionSpinner.adapter = adapter

                val reports = api.getArray(
                    "/reports"
                ).objects()

                reportsContainer.removeAllViews()

                if (reports.isEmpty()) {
                    reportsContainer.addSmartCard(
                        "Aucun signalement",
                        "Vos rapports apparaîtront ici."
                    )
                }

                reports.forEach { report ->
                    reportsContainer.addSmartCard(
                        title = report.field("title"),
                        description = report.field("description"),
                        status = "Statut : ${report.field("status")}"
                    )
                }
            } catch (e: Exception) {
                message(
                    e.message ?: "Chargement des données impossible."
                )
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun sendReport() {
        val position = missionSpinner.selectedItemPosition

        if (position !in missions.indices) {
            message("Sélectionnez une mission.")
            return
        }

        val title = titleInput.text.toString().trim()
        val description = descriptionInput.text.toString().trim()

        if (title.isBlank() || description.isBlank()) {
            message("Complétez le titre et la description.")
            return
        }

        val missionId = missions[position].optLong("id")

        lifecycleScope.launch {
            progress.visibility = View.VISIBLE

            try {
                api.post(
                    "/reports",
                    JSONObject()
                        .put("missionId", missionId)
                        .put("title", title)
                        .put("description", description)
                )

                titleInput.setText("")
                descriptionInput.setText("")

                message("Signalement envoyé avec succès.")
                loadData()
            } catch (e: Exception) {
                message(
                    e.message ?: "Signalement non envoyé."
                )
            } finally {
                progress.visibility = View.GONE
            }
        }
    }
}