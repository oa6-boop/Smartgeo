package com.smartgeo.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var api: SmartGeoApi

    private lateinit var username: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView

    private val notificationPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            openDashboard()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        api = SmartGeoApi(applicationContext)

        if (api.hasSession()) {
            finishLogin()
            return
        }

        setContentView(R.layout.activity_login)

        username = findViewById(R.id.inputUsername)
        password = findViewById(R.id.inputPassword)
        loginButton = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progressLogin)
        errorView = findViewById(R.id.tvLoginError)

        loginButton.setOnClickListener {
            performLogin()
        }
    }

    private fun performLogin() {

        val user = username.text.toString().trim()
        val pass = password.text.toString()

        if (user.isBlank() || pass.isBlank()) {
            showError(
                "Saisissez votre identifiant et mot de passe."
            )
            return
        }

        errorView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        loginButton.isEnabled = false

        lifecycleScope.launch {

            try {
                api.login(user, pass)

                finishLogin()

            } catch (error: Exception) {
                showError(
                    error.message ?: "Connexion impossible."
                )
            } finally {
                progress.visibility = View.GONE
                loginButton.isEnabled = true
            }
        }
    }

    private fun finishLogin() {

        // Conserve la synchronisation GPS existante.
        GpsSyncScheduler.start(applicationContext)

        // Enregistre le terminal Android pour FCM.
        SmartGeoPush.register(applicationContext)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            openDashboard()
        }
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun openDashboard() {

        val intent = Intent(
            this,
            MainActivity::class.java
        ).apply {

            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)
        finish()
    }
}