
package com.smartgeo.android

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class ProfileActivity : AppCompatActivity() {

    private lateinit var api: SmartGeoApi

    private lateinit var nameInput: TextInputEditText
    private lateinit var emailInput: TextInputEditText
    private lateinit var phoneInput: TextInputEditText

    private lateinit var progress: ProgressBar

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_profile)

        api = SmartGeoApi(applicationContext)

        setupNavigation(R.id.nav_profile)

        nameInput =
            findViewById(R.id.inputProfileName)

        emailInput =
            findViewById(R.id.inputProfileEmail)

        phoneInput =
            findViewById(R.id.inputProfilePhone)

        progress =
            findViewById(R.id.progressProfile)

        findViewById<MaterialButton>(
            R.id.btnSaveProfile
        ).setOnClickListener {

            saveProfile()
        }

        val logoutButton =
            findViewById<MaterialButton>(R.id.btnLogout)

        logoutButton.setOnClickListener {

            if (!busy) {
                logout()
            }
        }

        // Ajouter le bouton de securite
        // au layout XML existant.

        val parent =
            logoutButton.parent as? LinearLayout

        if (parent != null) {

            val passwordButton =
                MaterialButton(this).apply {

                    text = "Modifier mon mot de passe"

                    cornerRadius = dp(15)

                    backgroundTintList =
                        ContextCompat.getColorStateList(
                            this@ProfileActivity,
                            R.color.sg_primary
                        )

                    layoutParams =
                        LinearLayout.LayoutParams(

                            LinearLayout.LayoutParams.MATCH_PARENT,

                            dp(54)

                        ).apply {

                            topMargin = dp(20)
                        }

                    setOnClickListener {

                        if (!busy) {
                            passwordDialog()
                        }
                    }
                }

            parent.addView(
                passwordButton,
                parent.indexOfChild(logoutButton)
            )
        }

        loadProfile()
    }

    private fun showLoading(value: Boolean) {

        busy = value

        progress.visibility =

            if (value) {

                View.VISIBLE

            } else {

                View.GONE
            }
    }

    private fun loadProfile() {

        if (busy) return

        lifecycleScope.launch {

            showLoading(true)

            try {

                val user =
                    api.getObject("/users/me")

                findViewById<TextView>(
                    R.id.tvProfileUsername
                ).text =
                    "Identifiant : ${user.field("username")}"

                findViewById<TextView>(
                    R.id.tvProfileRole
                ).text =
                    "Rôle : ${user.field("role")}"

                nameInput.setText(
                    user.field("fullName")
                )

                emailInput.setText(
                    user.field("email")
                )

                phoneInput.setText(
                    user.field("phone")
                )

            } catch (e: CancellationException) {

                throw e

            } catch (e: Exception) {

                message(
                    e.message ?: "Profil indisponible."
                )

            } finally {

                showLoading(false)
            }
        }
    }

    private fun saveProfile() {

        if (busy) return

        val name =
            nameInput.text.toString().trim()

        val email =
            emailInput.text.toString().trim()

        val phone =
            phoneInput.text.toString().trim()

        if (name.isBlank()) {

            message("Le nom est obligatoire.")

            return
        }

        lifecycleScope.launch {

            showLoading(true)

            var saved = false

            try {

                api.put(

                    "/users/me",

                    JSONObject()
                        .put("fullName", name)
                        .put("email", email)
                        .put("phone", phone)
                )

                saved = true

                message("Profil enregistré.")

            } catch (e: CancellationException) {

                throw e

            } catch (e: Exception) {

                message(
                    e.message ?: "Modification impossible."
                )

            } finally {

                showLoading(false)
            }

            if (saved) {
                loadProfile()
            }
        }
    }

    private fun passwordDialog() {

        val container =
            LinearLayout(this).apply {

                orientation = LinearLayout.VERTICAL

                setPadding(
                    dp(24),
                    dp(12),
                    dp(24),
                    dp(8)
                )
            }

        fun passwordInput(label: String):
                TextInputEditText {

            return TextInputEditText(
                this@ProfileActivity
            ).apply {

                hint = label

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD

                isSaveEnabled = false

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {

                        bottomMargin = dp(14)
                    }

                container.addView(this)
            }
        }

        val oldInput =
            passwordInput("Ancien mot de passe")

        val newInput =
            passwordInput("Nouveau mot de passe")

        val confirmInput =
            passwordInput("Confirmer le mot de passe")

        val dialog = AlertDialog.Builder(this)

            .setTitle("Sécurité du compte")

            .setView(container)

            .setNegativeButton("Annuler", null)

            .setPositiveButton("Modifier", null)

            .create()

        dialog.setOnShowListener {

            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {

                if (busy) return@setOnClickListener

                val oldPassword =
                    oldInput.text.toString()

                val newPassword =
                    newInput.text.toString()

                val confirmation =
                    confirmInput.text.toString()

                if (oldPassword.isEmpty()) {

                    oldInput.error =
                        "Champ obligatoire"

                    return@setOnClickListener
                }

                if (
                    newPassword.length < 8 ||
                    newPassword.isBlank() ||
                    newPassword.toByteArray(
                        Charsets.UTF_8
                    ).size > 72
                ) {

                    newInput.error =
                        "8 caractères minimum, 72 octets maximum"

                    return@setOnClickListener
                }

                if (newPassword != confirmation) {

                    confirmInput.error =
                        "Les mots de passe sont différents"

                    return@setOnClickListener
                }

                if (oldPassword == newPassword) {

                    newInput.error =
                        "Choisissez un nouveau mot de passe"

                    return@setOnClickListener
                }

                lifecycleScope.launch {

                    showLoading(true)

                    dialog.setCancelable(false)

                    dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                    ).isEnabled = false

                    dialog.getButton(
                        AlertDialog.BUTTON_NEGATIVE
                    ).isEnabled = false

                    try {

                        api.put(

                            "/account/password",

                            JSONObject()

                                .put(
                                    "oldPassword",
                                    oldPassword
                                )

                                .put(
                                    "newPassword",
                                    newPassword
                                )
                        )

                        oldInput.text?.clear()

                        newInput.text?.clear()

                        confirmInput.text?.clear()

                        dialog.dismiss()

                        message(
                            "Mot de passe modifié."
                        )

                    } catch (e: CancellationException) {

                        throw e

                    } catch (e: Exception) {

                        message(
                            e.message ?:
                            "Modification impossible."
                        )

                    } finally {

                        showLoading(false)

                        dialog.setCancelable(true)

                        dialog.getButton(
                            AlertDialog.BUTTON_POSITIVE
                        ).isEnabled = true

                        dialog.getButton(
                            AlertDialog.BUTTON_NEGATIVE
                        ).isEnabled = true
                    }
                }
            }
        }

        dialog.setOnDismissListener {

            oldInput.text?.clear()

            newInput.text?.clear()

            confirmInput.text?.clear()
        }

        dialog.show()
    }

    private fun logout() {

        lifecycleScope.launch {

            showLoading(true)

            try {

                withTimeoutOrNull(5000L) {

                    SmartGeoPush.unregister(
                        applicationContext
                    )
                }

            } catch (e: CancellationException) {

                throw e

            } catch (_: Exception) {

                // Fermeture locale de la session.
            }

            stopService(

                Intent(
                    this@ProfileActivity,
                    TrackingService::class.java
                )
            )

            api.logout()

            val intent = Intent(
                this@ProfileActivity,
                LoginActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            startActivity(intent)

            finish()
        }
    }
}
