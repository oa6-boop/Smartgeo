
package com.smartgeo.android

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MessagesActivity : AppCompatActivity() {

    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }

    private data class SavedFile(
        val name: String,
        val bytes: ByteArray
    )

    private lateinit var api: SmartGeoApi
    private lateinit var spinner: Spinner
    private lateinit var container: LinearLayout
    private lateinit var input: TextInputEditText
    private lateinit var progress: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var attachButton: MaterialButton
    private lateinit var sendButton: MaterialButton

    private var contacts = emptyList<JSONObject>()
    private var selectedId = -1L
    private var busy = false
    private var pendingDownload: SavedFile? = null

    private val chooseAttachment =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->

            if (uri != null) {
                uploadAttachment(uri)
            }
        }

    private val saveAttachment =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/octet-stream"
            )
        ) { uri ->

            val file = pendingDownload
            pendingDownload = null

            if (uri != null && file != null) {

                lifecycleScope.launch {

                    try {

                        withContext(Dispatchers.IO) {

                            contentResolver.openOutputStream(uri)
                                ?.use { output ->

                                    output.write(file.bytes)

                                } ?: error(
                                "Impossible de créer le fichier."
                            )
                        }

                        notice("Fichier enregistré.")

                    } catch (e: Exception) {

                        notice(
                            e.message
                                ?: "Enregistrement impossible."
                        )
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_messages)

        api = SmartGeoApi(applicationContext)

        spinner = findViewById(
            R.id.spinnerContacts
        )

        container = findViewById(
            R.id.messagesContainer
        )

        input = findViewById(
            R.id.inputMessage
        )

        progress = findViewById(
            R.id.progressMessages
        )

        scroll = findViewById(
            R.id.scrollMessages
        )

        attachButton = findViewById(
            R.id.btnAttachFile
        )

        sendButton = findViewById(
            R.id.btnSendMessage
        )

        setupNavigation(
            R.id.nav_messages
        ) { categories ->

            if ("messages" in categories) {
                loadConversation()
            }
        }

        attachButton.setOnClickListener {

            if (selectedId <= 0) {

                notice(
                    "Sélectionnez un administrateur."
                )

            } else {

                chooseAttachment.launch("*/*")
            }
        }

        sendButton.setOnClickListener {
            sendText()
        }

        loadContacts()
    }

    override fun onResume() {
        super.onResume()

        if (selectedId > 0) {
            loadConversation()
        }
    }

    private fun setBusy(value: Boolean) {

        busy = value

        progress.visibility =
            if (value) {
                View.VISIBLE
            } else {
                View.GONE
            }

        attachButton.isEnabled = !value
        sendButton.isEnabled = !value
    }

    private fun notice(text: String) {

        Snackbar.make(
            findViewById(android.R.id.content),
            text,
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun dp(value: Int): Int {

        return (
                value * resources.displayMetrics.density
                ).toInt()
    }

    // ============================================
    // CHARGEMENT DES CONTACTS
    // ============================================

    private fun loadContacts() {

        lifecycleScope.launch {

            setBusy(true)

            try {

                contacts = api.getArray(
                    "/messages/contacts"
                ).objects()

                val labels = contacts.map {

                    it.field(
                        "fullName",
                        "username"
                    ).ifBlank {
                        "Administrateur"
                    }
                }

                val adapter = ArrayAdapter(
                    this@MessagesActivity,
                    android.R.layout.simple_spinner_item,
                    labels
                )

                adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item
                )

                spinner.adapter = adapter

                spinner.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {

                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {

                            if (position !in contacts.indices) {
                                return
                            }

                            selectedId = contacts[position]
                                .optLong(
                                    "id",
                                    -1L
                                )

                            loadConversation()
                        }

                        override fun onNothingSelected(
                            parent: AdapterView<*>?
                        ) {
                            selectedId = -1L
                        }
                    }

                if (contacts.isEmpty()) {

                    showEmpty(
                        "Aucun administrateur disponible."
                    )
                }

            } catch (e: Exception) {

                notice(
                    e.message
                        ?: "Chargement des contacts impossible."
                )

            } finally {

                setBusy(false)
            }
        }
    }

    private fun showEmpty(message: String) {

        container.removeAllViews()

        val empty = TextView(this).apply {

            this.text = message

            textSize = 15f

            gravity = Gravity.CENTER

            setPadding(
                dp(20),
                dp(45),
                dp(20),
                dp(45)
            )

            setTextColor(
                ContextCompat.getColor(
                    this@MessagesActivity,
                    R.color.sg_muted
                )
            )
        }

        container.addView(empty)
    }

    // ============================================
    // CONVERSATION
    // ============================================

    private fun loadConversation() {

        if (selectedId <= 0) return

        val contactId = selectedId

        lifecycleScope.launch {

            try {

                val messages = api.getArray(
                    "/messages/conversation/$contactId"
                ).objects()

                if (selectedId != contactId) {
                    return@launch
                }

                container.removeAllViews()

                if (messages.isEmpty()) {

                    showEmpty(
                        "Aucun message. Commencez la conversation."
                    )

                } else {

                    messages.forEach { message ->
                        addBubble(message)
                    }
                }

                scroll.post {

                    scroll.fullScroll(
                        View.FOCUS_DOWN
                    )
                }

                // Marquer les messages recus comme lus.

                messages
                    .filter { item ->

                        item.optLong(
                            "receiver_id"
                        ) == api.userId() &&

                                item.isNull("read_at")
                    }
                    .take(30)
                    .forEach { item ->

                        try {

                            api.put(
                                "/messages/${item.getLong("id")}/read",
                                JSONObject()
                            )

                        } catch (_: Exception) {

                            // Ne bloque pas le chat.
                        }
                    }

            } catch (e: Exception) {

                notice(
                    e.message
                        ?: "Conversation indisponible."
                )
            }
        }
    }

    // ============================================
    // AFFICHAGE DES BULLES
    // ============================================

    private fun addBubble(item: JSONObject) {

        val mine = item.optLong(
            "sender_id"
        ) == api.userId()

        val messageContent = item.field(
            "content"
        )

        val attachment = messageContent.startsWith(
            "[FICHIER] "
        )

        val row = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = if (mine) {
                Gravity.END
            } else {
                Gravity.START
            }
        }

        val bubble = MaterialCardView(this).apply {

            radius = dp(18).toFloat()

            cardElevation = dp(1).toFloat()

            setCardBackgroundColor(
                ContextCompat.getColor(
                    this@MessagesActivity,
                    if (mine) {
                        R.color.sg_light_blue
                    } else {
                        R.color.sg_surface
                    }
                )
            )

            strokeWidth = if (mine) {
                0
            } else {
                dp(1)
            }

            strokeColor = ContextCompat.getColor(
                this@MessagesActivity,
                R.color.sg_border
            )
        }

        // CORRECTION : utiliser this.text pour
        // modifier la propriete du TextView.

        val content = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(14),
                dp(11),
                dp(14),
                dp(12)
            )
        }

        val who = TextView(this).apply {

            this.text = if (mine) {
                "Moi"
            } else {
                "ADMIN"
            }

            textSize = 12f

            setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

            setTextColor(
                ContextCompat.getColor(
                    this@MessagesActivity,
                    R.color.sg_primary
                )
            )
        }

        val body = TextView(this).apply {

            this.text = if (attachment) {

                "📎 " +
                        messageContent.removePrefix(
                            "[FICHIER] "
                        ) +
                        "\nAppuyer pour enregistrer"

            } else {

                messageContent
            }

            textSize = 15f

            maxWidth = (
                    resources.displayMetrics.widthPixels * 0.67f
                    ).toInt()

            setPadding(
                0,
                dp(6),
                0,
                0
            )

            setTextColor(
                ContextCompat.getColor(
                    this@MessagesActivity,
                    R.color.sg_text
                )
            )
        }

        content.addView(who)
        content.addView(body)

        bubble.addView(content)

        if (attachment) {

            bubble.setOnClickListener {

                downloadAttachment(
                    item.optLong("id")
                )
            }
        }

        row.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                bottomMargin = dp(12)
            }
        )
    }

    // ============================================
    // ENVOI DES MESSAGES TEXTES
    // ============================================

    private fun sendText() {

        if (busy) return

        val contactId = selectedId

        val content = input.text
            .toString()
            .trim()

        if (contactId <= 0) {

            notice(
                "Sélectionnez un administrateur."
            )

            return
        }

        if (
            content.isBlank() ||
            content.length > 4000
        ) {

            notice(
                "Message obligatoire (4000 caractères maximum)."
            )

            return
        }

        lifecycleScope.launch {

            setBusy(true)

            try {

                api.post(
                    "/messages",
                    JSONObject()
                        .put(
                            "receiverId",
                            contactId
                        )
                        .put(
                            "content",
                            content
                        )
                )

                input.setText("")

                if (selectedId == contactId) {
                    loadConversation()
                }

            } catch (e: Exception) {

                notice(
                    e.message ?: "Envoi impossible."
                )

            } finally {

                setBusy(false)
            }
        }
    }

    // ============================================
    // ENVOI DES PIECES JOINTES
    // ============================================

    private fun uploadAttachment(uri: Uri) {

        if (
            busy ||
            selectedId <= 0
        ) {
            return
        }

        val contactId = selectedId

        lifecycleScope.launch {

            setBusy(true)

            try {

                val name = getFileName(uri)

                val mime = getMimeType(
                    uri,
                    name
                )

                val allowed = setOf(
                    "image/jpeg",
                    "image/png",
                    "application/pdf",
                    "text/plain"
                )

                if (mime !in allowed) {

                    error(
                        "Format accepté : JPG, PNG, PDF ou TXT."
                    )
                }

                val bytes = withContext(
                    Dispatchers.IO
                ) {

                    readLimitedFile(uri)
                }

                val base64 = withContext(
                    Dispatchers.Default
                ) {

                    Base64.encodeToString(
                        bytes,
                        Base64.NO_WRAP
                    )
                }

                api.post(
                    "/message-files",
                    JSONObject()
                        .put(
                            "receiverId",
                            contactId
                        )
                        .put(
                            "fileName",
                            name
                        )
                        .put(
                            "mimeType",
                            mime
                        )
                        .put(
                            "base64",
                            base64
                        )
                )

                notice(
                    "Pièce jointe envoyée."
                )

                if (selectedId == contactId) {

                    loadConversation()
                }

            } catch (e: Exception) {

                notice(
                    e.message
                        ?: "Envoi du fichier impossible."
                )

            } finally {

                setBusy(false)
            }
        }
    }

    private fun getFileName(uri: Uri): String {

        var name = "document"

        contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {

                name = cursor.getString(0)
                    ?: name
            }
        }

        return name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .take(180)
    }

    private fun getMimeType(
        uri: Uri,
        filename: String
    ): String {

        val ext = filename
            .substringAfterLast(
                '.',
                ""
            )
            .lowercase()

        val fromExtension = when (ext) {

            "jpg", "jpeg" -> "image/jpeg"

            "png" -> "image/png"

            "pdf" -> "application/pdf"

            "txt" -> "text/plain"

            else -> "application/octet-stream"
        }

        val provided = contentResolver.getType(
            uri
        )

        return if (
            provided != null &&
            provided != "application/octet-stream" &&
            provided in setOf(
                "image/jpeg",
                "image/png",
                "application/pdf",
                "text/plain"
            )
        ) {

            provided

        } else {

            fromExtension
        }
    }

    private fun readLimitedFile(
        uri: Uri
    ): ByteArray {

        val output = ByteArrayOutputStream()

        contentResolver.openInputStream(uri)?.use { stream ->

            val buffer = ByteArray(8192)

            while (true) {

                val count = stream.read(buffer)

                if (count == -1) {
                    break
                }

                if (
                    output.size() + count >
                    MAX_FILE_BYTES
                ) {

                    error(
                        "Le fichier dépasse 2 Mo."
                    )
                }

                output.write(
                    buffer,
                    0,
                    count
                )
            }

        } ?: error(
            "Impossible de lire le fichier."
        )

        if (output.size() == 0) {

            error(
                "Le fichier est vide."
            )
        }

        return output.toByteArray()
    }

    // ============================================
    // TELECHARGEMENT DES PIECES JOINTES
    // ============================================

    private fun downloadAttachment(
        messageId: Long
    ) {

        if (messageId <= 0) {
            return
        }

        lifecycleScope.launch {

            setBusy(true)

            try {

                val result = api.getObject(
                    "/message-files/$messageId"
                )

                val bytes = withContext(
                    Dispatchers.Default
                ) {

                    Base64.decode(
                        result.getString(
                            "base64"
                        ),
                        Base64.DEFAULT
                    )
                }

                val name = result.field(
                    "fileName"
                )
                    .ifBlank {
                        "piece-jointe"
                    }
                    .substringAfterLast('/')
                    .substringAfterLast('\\')

                pendingDownload = SavedFile(
                    name,
                    bytes
                )

                saveAttachment.launch(name)

            } catch (e: Exception) {

                notice(
                    e.message
                        ?: "Téléchargement impossible."
                )

            } finally {

                setBusy(false)
            }
        }
    }
}
