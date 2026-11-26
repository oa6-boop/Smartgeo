package com.smartgeo.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GpsQueue(context: Context) {

    data class PendingPosition(
        val uuid: String,
        val payload: JSONObject
    )

    companion object {
        private const val DATABASE_NAME = "smartgeo_gps_queue.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "pending_positions"
        private const val KEY_ALIAS = "smartgeo_gps_aes_key"

        private const val MAX_PENDING = 1000
    }

    private val helper = DatabaseHelper(
        context.applicationContext
    )

    private class DatabaseHelper(
        context: Context
    ) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE pending_positions (
                    user_id INTEGER NOT NULL,
                    uuid TEXT NOT NULL,
                    encrypted_payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (user_id, uuid)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX idx_pending_created
                ON pending_positions(user_id, created_at)
                """.trimIndent()
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            // Les migrations seront ajoutees si le
            // schema local evolue dans une autre version.
        }
    }

    fun add(
        userId: Long,
        position: JSONObject
    ) {
        require(userId > 0) {
            "Utilisateur GPS invalide."
        }

        val uuid = position.getString(
            "clientPositionId"
        )

        val db = helper.writableDatabase

        db.beginTransaction()

        try {
            val existing = db.rawQuery(
                """
                SELECT 1 FROM pending_positions
                WHERE user_id=? AND uuid=?
                """.trimIndent(),
                arrayOf(
                    userId.toString(),
                    uuid
                )
            ).use {
                it.moveToFirst()
            }

            if (existing) {
                db.setTransactionSuccessful()
                return
            }

            val count = countInternal(
                db,
                userId
            )

            if (count >= MAX_PENDING) {
                throw IllegalStateException(
                    "La file GPS locale est pleine. " +
                            "Synchronisez les positions avant de continuer."
                )
            }

            val encrypted = encrypt(
                position.toString()
            )

            val values = ContentValues().apply {
                put("user_id", userId)
                put("uuid", uuid)
                put("encrypted_payload", encrypted)
                put(
                    "created_at",
                    System.currentTimeMillis()
                )
            }

            db.insertOrThrow(
                TABLE,
                null,
                values
            )

            db.setTransactionSuccessful()

        } finally {
            db.endTransaction()
        }
    }

    fun count(userId: Long): Int {
        if (userId <= 0) return 0

        return countInternal(
            helper.readableDatabase,
            userId
        )
    }

    private fun countInternal(
        db: SQLiteDatabase,
        userId: Long
    ): Int {

        db.rawQuery(
            """
            SELECT COUNT(*)
            FROM pending_positions
            WHERE user_id=?
            """.trimIndent(),
            arrayOf(userId.toString())
        ).use { cursor ->

            return if (cursor.moveToFirst()) {
                cursor.getInt(0)
            } else {
                0
            }
        }
    }

    fun pending(
        userId: Long,
        limit: Int = 50
    ): List<PendingPosition> {

        val result = mutableListOf<PendingPosition>()

        if (userId <= 0) return result

        helper.readableDatabase.query(
            TABLE,
            arrayOf("uuid", "encrypted_payload"),
            "user_id=?",
            arrayOf(userId.toString()),
            null,
            null,
            "created_at ASC",
            limit.coerceIn(1, 100).toString()
        ).use { cursor ->

            val uuidIndex = cursor.getColumnIndexOrThrow(
                "uuid"
            )

            val payloadIndex = cursor.getColumnIndexOrThrow(
                "encrypted_payload"
            )

            while (cursor.moveToNext()) {
                val uuid = cursor.getString(uuidIndex)

                val decrypted = decrypt(
                    cursor.getString(payloadIndex)
                )

                result.add(
                    PendingPosition(
                        uuid = uuid,
                        payload = JSONObject(decrypted)
                    )
                )
            }
        }

        return result
    }

    fun delete(
        userId: Long,
        uuid: String
    ) {
        helper.writableDatabase.delete(
            TABLE,
            "user_id=? AND uuid=?",
            arrayOf(
                userId.toString(),
                uuid
            )
        )
    }

    fun clearUser(userId: Long) {
        if (userId <= 0) return

        helper.writableDatabase.delete(
            TABLE,
            "user_id=?",
            arrayOf(userId.toString())
        )
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(
            "AndroidKeyStore"
        )

        store.load(null)

        val existing = store.getKey(
            KEY_ALIAS,
            null
        ) as? SecretKey

        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(
                KeyProperties.BLOCK_MODE_GCM
            )
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setKeySize(256)
            .build()

        generator.init(specification)

        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(
            "AES/GCM/NoPadding"
        )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey()
        )

        val encrypted = cipher.doFinal(
            text.toByteArray(Charsets.UTF_8)
        )

        val iv = Base64.encodeToString(
            cipher.iv,
            Base64.NO_WRAP
        )

        val payload = Base64.encodeToString(
            encrypted,
            Base64.NO_WRAP
        )

        return "$iv:$payload"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(
            ":",
            limit = 2
        )

        require(parts.size == 2) {
            "Position locale chiffree invalide."
        }

        val iv = Base64.decode(
            parts[0],
            Base64.NO_WRAP
        )

        val encrypted = Base64.decode(
            parts[1],
            Base64.NO_WRAP
        )

        val cipher = Cipher.getInstance(
            "AES/GCM/NoPadding"
        )

        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, iv)
        )

        return String(
            cipher.doFinal(encrypted),
            Charsets.UTF_8
        )
    }
}