
package com.smartgeo.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

fun Activity.message(text: String) {
    Toast.makeText(
        this,
        text,
        Toast.LENGTH_LONG
    ).show()
}

fun Activity.openScreen(
    target: Class<out Activity>
) {
    startActivity(Intent(this, target))
}

/**
 * Navigation SmartGeo + notifications.
 *
 * La verification est active uniquement lorsque
 * l'Activity est visible.
 */
fun AppCompatActivity.setupNavigation(
    selected: Int,
    onNewEvents: ((Set<String>) -> Unit)? = null
) {

    val navigation = findViewById<BottomNavigationView>(
        R.id.bottomNavigation
    )

    val api = SmartGeoApi(applicationContext)
    val userId = api.userId()

    fun categoryOf(itemId: Int): String {
        return when (itemId) {
            R.id.nav_missions -> "missions"
            R.id.nav_messages -> "messages"
            R.id.nav_gps -> "gps"
            R.id.nav_home -> "reports"
            else -> ""
        }
    }

    fun destinationOf(itemId: Int): Class<out Activity> {
        return when (itemId) {
            R.id.nav_home -> MainActivity::class.java
            R.id.nav_missions -> MissionsActivity::class.java
            R.id.nav_gps -> GpsActivity::class.java
            R.id.nav_messages -> MessagesActivity::class.java
            R.id.nav_profile -> ProfileActivity::class.java
            else -> MainActivity::class.java
        }
    }

    fun updateBadges() {

        val associations = listOf(
            R.id.nav_missions to "missions",
            R.id.nav_messages to "messages",
            R.id.nav_gps to "gps",
            R.id.nav_home to "reports"
        )

        associations.forEach { (itemId, category) ->

            val count = SmartGeoNotifications.unreadCount(
                this,
                userId,
                category
            )

            if (count > 0) {
                val badge = navigation.getOrCreateBadge(
                    itemId
                )

                badge.isVisible = true
                badge.number = count
                badge.maxCharacterCount = 3

                badge.backgroundColor =
                    ContextCompat.getColor(
                        this,
                        R.color.sg_error
                    )

                badge.badgeTextColor =
                    ContextCompat.getColor(
                        this,
                        R.color.sg_surface
                    )
            } else {
                navigation.removeBadge(itemId)
            }
        }
    }

    fun selectSection(itemId: Int) {

        val category = categoryOf(itemId)

        if (category.isNotBlank()) {
            SmartGeoNotifications.markRead(
                this,
                userId,
                category
            )
        }

        updateBadges()

        val destination = destinationOf(itemId)

        if (destination == this::class.java) {
            // Si l'utilisateur touche de nouveau
            // l'onglet actif, rafraichir si possible.
            if (category.isNotBlank()) {
                onNewEvents?.invoke(setOf(category))
            }
            return
        }

        startActivity(
            Intent(this, destination)
        )
        finish()
    }

    // L'ecran ouvert correspond a un onglet consulte.
    val currentCategory = categoryOf(selected)

    if (currentCategory.isNotBlank()) {
        SmartGeoNotifications.markRead(
            this,
            userId,
            currentCategory
        )
    }

    navigation.selectedItemId = selected

    navigation.setOnItemSelectedListener { item ->
        selectSection(item.itemId)
        true
    }

    navigation.setOnItemReselectedListener { item ->
        selectSection(item.itemId)
    }

    updateBadges()

    // Recherche des nouveautes pendant que l'agent
    // utilise l'application.
    lifecycleScope.launch {

        repeatOnLifecycle(Lifecycle.State.STARTED) {

            while (currentCoroutineContext().isActive) {

                try {
                    val alerts = SmartGeoNotifications.check(
                        this@setupNavigation,
                        api
                    )

                    updateBadges()

                    if (alerts.isNotEmpty()) {

                        val categories = alerts
                            .map { it.category }
                            .toSet()

                        onNewEvents?.invoke(categories)

                        val first = alerts.first()

                        val notificationText =
                            if (alerts.size == 1) {
                                first.message
                            } else {
                                "${alerts.size} types de nouvelles notifications."
                            }

                        val target: Class<out Activity> =
                            when (first.category) {
                                "missions" ->
                                    MissionsActivity::class.java

                                "messages" ->
                                    MessagesActivity::class.java

                                "gps" ->
                                    GpsActivity::class.java

                                "reports" ->
                                    ReportsActivity::class.java

                                else ->
                                    MainActivity::class.java
                            }

                        Snackbar.make(
                            navigation,
                            notificationText,
                            Snackbar.LENGTH_LONG
                        )
                            .setAnchorView(navigation)
                            .setAction("Voir") {

                                if (
                                    target ==
                                    this@setupNavigation::class.java
                                ) {
                                    SmartGeoNotifications.markRead(
                                        this@setupNavigation,
                                        userId,
                                        first.category
                                    )

                                    updateBadges()

                                    onNewEvents?.invoke(
                                        setOf(first.category)
                                    )
                                } else {
                                    openScreen(target)
                                    finish()
                                }
                            }
                            .show()
                    }

                } catch (error: Exception) {
                    Log.w(
                        "SmartGeoNotifications",
                        "Verification des notifications impossible",
                        error
                    )
                }

                delay(15000L)
            }
        }
    }
}

/**
 * Carte graphique reutilisable.
 */
fun LinearLayout.addSmartCard(
    title: String,
    description: String,
    status: String = "",
    buttonText: String? = null,
    onButton: (() -> Unit)? = null
) {

    val context = this.context

    val card = MaterialCardView(context).apply {

        radius = context.dp(20).toFloat()
        cardElevation = context.dp(1).toFloat()
        strokeWidth = context.dp(1)

        strokeColor = ContextCompat.getColor(
            context,
            R.color.sg_border
        )

        setCardBackgroundColor(
            ContextCompat.getColor(
                context,
                R.color.sg_surface
            )
        )
    }

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL

        setPadding(
            context.dp(16),
            context.dp(16),
            context.dp(16),
            context.dp(16)
        )
    }

    val titleView = TextView(context).apply {
        text = title
        textSize = 16f

        setTextColor(
            ContextCompat.getColor(
                context,
                R.color.sg_text
            )
        )

        setTypeface(
            Typeface.DEFAULT,
            Typeface.BOLD
        )
    }

    val descriptionView = TextView(context).apply {
        text = description
        textSize = 13f

        setTextColor(
            ContextCompat.getColor(
                context,
                R.color.sg_muted
            )
        )

        setPadding(
            0,
            context.dp(7),
            0,
            0
        )
    }

    content.addView(titleView)
    content.addView(descriptionView)

    if (status.isNotBlank()) {

        val statusView = TextView(context).apply {
            text = status
            textSize = 12f

            setTextColor(
                ContextCompat.getColor(
                    context,
                    R.color.sg_primary
                )
            )

            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            setPadding(
                0,
                context.dp(10),
                0,
                0
            )
        }

        content.addView(statusView)
    }

    if (buttonText != null && onButton != null) {

        val action = MaterialButton(context).apply {
            text = buttonText
            isAllCaps = false
            cornerRadius = context.dp(12)

            backgroundTintList =
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        R.color.sg_primary
                    )
                )

            setOnClickListener {
                onButton()
            }
        }

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(46)
        ).apply {
            topMargin = context.dp(12)
        }

        content.addView(
            action,
            buttonParams
        )
    }

    card.addView(content)

    val params = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = context.dp(12)
    }

    addView(
        card,
        params
    )
}
