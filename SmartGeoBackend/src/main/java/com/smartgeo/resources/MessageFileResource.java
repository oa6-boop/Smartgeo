
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Path("/message-files")
@Secured
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MessageFileResource extends BaseResource {

    private static final int MAX_FILE_SIZE =
            2 * 1024 * 1024;

    private static final Set<String> ALLOWED_TYPES =
            Set.of(
                    "image/jpeg",
                    "image/png",
                    "application/pdf",
                    "text/plain"
            );

    public record FileRequest(
            long receiverId,
            String fileName,
            String mimeType,
            String base64
    ) {}

    // =========================================
    // ENVOYER UNE PIECE JOINTE
    // POST /api/message-files
    // =========================================

    @POST
    public Response upload(FileRequest request) {

        if (currentId() <= 0) {
            return error(401, "Session invalide.");
        }

        if (request == null ||
                request.receiverId() <= 0 ||
                request.receiverId() == currentId() ||
                request.fileName() == null ||
                request.mimeType() == null ||
                request.base64() == null) {

            return error(
                    400,
                    "Informations du fichier invalides."
            );
        }

        String mime = request.mimeType()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (!ALLOWED_TYPES.contains(mime)) {
            return error(
                    400,
                    "Type de fichier non autorise."
            );
        }

        String fileName = request.fileName()
                .replace('\\', '/');

        fileName = fileName.substring(
                fileName.lastIndexOf('/') + 1
        ).trim();

        if (fileName.isBlank() ||
                fileName.length() > 180 ||
                fileName.chars().anyMatch(
                        c -> Character.isISOControl(c)
                )) {

            return error(
                    400,
                    "Nom de fichier invalide."
            );
        }

        String extension = fileName
                .substring(
                        fileName.lastIndexOf('.') + 1
                )
                .toLowerCase(Locale.ROOT);

        boolean extensionAllowed = switch (mime) {
            case "image/jpeg" ->
                    extension.equals("jpg") ||
                            extension.equals("jpeg");

            case "image/png" ->
                    extension.equals("png");

            case "application/pdf" ->
                    extension.equals("pdf");

            case "text/plain" ->
                    extension.equals("txt");

            default -> false;
        };

        if (!extensionAllowed) {
            return error(
                    400,
                    "Extension et type du fichier incompatibles."
            );
        }

        String encoded = request.base64();

        long maxEncodedLength =
                ((long) MAX_FILE_SIZE + 2L) / 3L * 4L;

        if (encoded.length() > maxEncodedLength + 8L) {
            return error(
                    413,
                    "Fichier trop volumineux."
            );
        }

        final byte[] fileBytes;

        try {
            fileBytes = Base64.getDecoder().decode(
                    encoded
            );

        } catch (IllegalArgumentException exception) {
            return error(
                    400,
                    "Contenu Base64 invalide."
            );
        }

        if (fileBytes.length == 0 ||
                fileBytes.length > MAX_FILE_SIZE) {

            return error(
                    413,
                    "Le fichier doit contenir entre 1 octet et 2 Mo."
            );
        }

        if (!validFileContent(mime, fileBytes)) {
            return error(
                    400,
                    "Le contenu du fichier ne correspond pas au format annonce."
            );
        }

        try (
                Connection connection =
                        Database.getConnection()
        ) {

            connection.setAutoCommit(false);

            try {

                if (!validContact(
                        connection,
                        request.receiverId()
                )) {

                    connection.rollback();

                    return error(
                            403,
                            "Destinataire non autorise ou inactif."
                    );
                }

                long messageId;

                // Enregistrer d'abord le message.

                String insertMessage = """
                        INSERT INTO messages (
                            sender_id,
                            receiver_id,
                            content,
                            sent_at
                        )
                        VALUES (?, ?, ?, NOW())
                        """;

                try (
                        PreparedStatement statement =
                                connection.prepareStatement(
                                        insertMessage,
                                        Statement.RETURN_GENERATED_KEYS
                                )
                ) {

                    statement.setLong(
                            1,
                            currentId()
                    );

                    statement.setLong(
                            2,
                            request.receiverId()
                    );

                    statement.setString(
                            3,
                            "[FICHIER] " + fileName
                    );

                    statement.executeUpdate();

                    try (
                            ResultSet keys =
                                    statement.getGeneratedKeys()
                    ) {

                        if (!keys.next()) {
                            throw new IllegalStateException(
                                    "Identifiant du message introuvable."
                            );
                        }

                        messageId = keys.getLong(1);
                    }
                }

                // Associer le fichier au message.

                String insertFile = """
                        INSERT INTO message_files (
                            message_id,
                            file_name,
                            mime_type,
                            size_bytes,
                            file_bytes
                        )
                        VALUES (?, ?, ?, ?, ?)
                        """;

                try (
                        PreparedStatement statement =
                                connection.prepareStatement(
                                        insertFile
                                )
                ) {

                    statement.setLong(
                            1,
                            messageId
                    );

                    statement.setString(
                            2,
                            fileName
                    );

                    statement.setString(
                            3,
                            mime
                    );

                    statement.setInt(
                            4,
                            fileBytes.length
                    );

                    statement.setBytes(
                            5,
                            fileBytes
                    );

                    statement.executeUpdate();
                }

                connection.commit();

                return Response.status(201)
                        .entity(
                                Map.of(
                                        "id",
                                        messageId,
                                        "message",
                                        "Piece jointe envoyee."
                                )
                        )
                        .build();

            } catch (Exception exception) {

                connection.rollback();
                throw exception;
            }

        } catch (Exception exception) {

            exception.printStackTrace();

            return error(
                    500,
                    "Impossible d'envoyer la piece jointe."
            );
        }
    }

    // =========================================
    // TELECHARGER UNE PIECE JOINTE
    // GET /api/message-files/{messageId}
    // =========================================

    @GET
    @Path("/{messageId}")
    public Response download(
            @PathParam("messageId") long messageId
    ) {

        if (currentId() <= 0) {
            return error(401, "Session invalide.");
        }

        if (messageId <= 0) {
            return error(
                    400,
                    "Identifiant de message invalide."
            );
        }

        String sql = """
                SELECT
                    f.file_name,
                    f.mime_type,
                    f.size_bytes,
                    f.file_bytes,
                    m.sender_id,
                    m.receiver_id
                FROM message_files f
                JOIN messages m
                    ON f.message_id = m.id
                WHERE f.message_id = ?
                """;

        try (
                Connection connection =
                        Database.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setLong(1, messageId);

            try (
                    ResultSet result =
                            statement.executeQuery()
            ) {

                if (!result.next()) {
                    return error(
                            404,
                            "Piece jointe introuvable."
                    );
                }

                long senderId = result.getLong(
                        "sender_id"
                );

                long receiverId = result.getLong(
                        "receiver_id"
                );

                // Seuls les participants a la
                // conversation peuvent lire le fichier.

                if (senderId != currentId() &&
                        receiverId != currentId()) {

                    return error(
                            403,
                            "Acces au fichier interdit."
                    );
                }

                byte[] bytes = result.getBytes(
                        "file_bytes"
                );

                return Response.ok(
                        Map.of(
                                "fileName",
                                result.getString(
                                        "file_name"
                                ),
                                "mimeType",
                                result.getString(
                                        "mime_type"
                                ),
                                "sizeBytes",
                                result.getInt(
                                        "size_bytes"
                                ),
                                "base64",
                                Base64.getEncoder()
                                        .encodeToString(bytes)
                        )
                ).build();
            }

        } catch (Exception exception) {

            exception.printStackTrace();

            return error(
                    500,
                    "Impossible de telecharger le fichier."
            );
        }
    }

    // =========================================
    // VALIDATION DU DESTINATAIRE
    // =========================================

    private boolean validContact(
            Connection connection,
            long receiverId
    ) throws Exception {

        String role = admin() ? "USER" : "ADMIN";

        String sql = """
                SELECT 1
                FROM users
                WHERE id = ?
                  AND role = ?
                  AND status = 'ACTIVE'
                """;

        try (
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setLong(1, receiverId);
            statement.setString(2, role);

            try (
                    ResultSet result =
                            statement.executeQuery()
            ) {
                return result.next();
            }
        }
    }

    // =========================================
    // VALIDATION DU CONTENU
    // =========================================

    private boolean validFileContent(
            String mime,
            byte[] bytes
    ) {

        if ("image/jpeg".equals(mime)) {
            return bytes.length >= 3 &&
                    (bytes[0] & 0xFF) == 0xFF &&
                    (bytes[1] & 0xFF) == 0xD8 &&
                    (bytes[2] & 0xFF) == 0xFF;
        }

        if ("image/png".equals(mime)) {

            byte[] signature = {
                    (byte) 0x89,
                    0x50,
                    0x4E,
                    0x47,
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A
            };

            if (bytes.length < signature.length) {
                return false;
            }

            for (int i = 0; i < signature.length; i++) {
                if (bytes[i] != signature[i]) {
                    return false;
                }
            }

            return true;
        }

        if ("application/pdf".equals(mime)) {
            return bytes.length >= 5 &&
                    bytes[0] == '%' &&
                    bytes[1] == 'P' &&
                    bytes[2] == 'D' &&
                    bytes[3] == 'F' &&
                    bytes[4] == '-';
        }

        if ("text/plain".equals(mime)) {
            try {
                String text = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(
                                CodingErrorAction.REPORT
                        )
                        .onUnmappableCharacter(
                                CodingErrorAction.REPORT
                        )
                        .decode(
                                ByteBuffer.wrap(bytes)
                        )
                        .toString();

                return text.indexOf('\0') < 0;

            } catch (CharacterCodingException error) {
                return false;
            }
        }

        return false;
    }

    // =========================================
    // REPONSE D'ERREUR JSON
    // =========================================

    private Response error(
            int status,
            String message
    ) {

        return Response.status(status)
                .entity(
                        Map.of(
                                "success",
                                false,
                                "message",
                                message
                        )
                )
                .build();
    }
}
