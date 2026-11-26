
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;

@Path("/admin/user-removal")
@Secured(roles = {"ADMIN"})
@Produces(MediaType.APPLICATION_JSON)
public class AdminUserRemovalResource extends BaseResource {

    private static final Logger LOG =
            Logger.getLogger(
                    AdminUserRemovalResource.class.getName()
            );

    @DELETE
    @Path("/{id}")
    public Response deleteUser(
            @PathParam("id") long userId
    ) {

        if (!admin()) {
            return forbidden();
        }

        if (userId <= 0) {
            return error(
                    400,
                    "Identifiant utilisateur invalide."
            );
        }

        if (userId == currentId()) {
            return error(
                    403,
                    "Vous ne pouvez pas supprimer votre propre compte."
            );
        }

        try (Connection c = Database.getConnection()) {

            c.setAutoCommit(false);

            try {

                // Verifier que l'administrateur est toujours actif.

                if (!exists(
                        c,
                        "SELECT id FROM users " +
                                "WHERE id=? AND role='ADMIN' " +
                                "AND status='ACTIVE' FOR UPDATE",
                        currentId()
                )) {

                    c.rollback();

                    return error(
                            403,
                            "Acces administrateur interdit."
                    );
                }

                // Verifier et verrouiller le compte cible.

                String role;

                try (
                        PreparedStatement p =
                                c.prepareStatement(
                                        "SELECT role FROM users " +
                                                "WHERE id=? FOR UPDATE"
                                )
                ) {

                    p.setLong(1, userId);

                    try (ResultSet r = p.executeQuery()) {

                        if (!r.next()) {

                            c.rollback();

                            return error(
                                    404,
                                    "Utilisateur introuvable."
                            );
                        }

                        role = r.getString("role");
                    }
                }

                // Ne jamais supprimer un compte ADMIN.

                if (!"USER".equals(role)) {

                    c.rollback();

                    return error(
                            403,
                            "Seuls les comptes USER peuvent etre supprimes."
                    );
                }

                // Proteger les missions creees par un ancien ADMIN.

                if (exists(
                        c,
                        "SELECT id FROM missions " +
                                "WHERE created_by=? LIMIT 1 FOR UPDATE",
                        userId
                )) {

                    c.rollback();

                    return error(
                            409,
                            "Ce compte a cree des missions. " +
                                    "Conservez-le ou desactivez-le."
                    );
                }

                // 1. Supprimer les demandes GPS.

                execute(
                        c,
                        "DELETE FROM location_requests " +
                                "WHERE user_id=? OR admin_id=?",
                        userId,
                        userId
                );

                // 2. Conserver les missions attribuees
                // en retirant leur affectation.

                int unassignedMissions = execute(
                        c,
                        "UPDATE missions " +
                                "SET assigned_user_id=NULL " +
                                "WHERE assigned_user_id=?",
                        userId
                );

                // 3. Supprimer les signalements de l'utilisateur.

                execute(
                        c,
                        "DELETE FROM reports WHERE user_id=?",
                        userId
                );

                // 4. Supprimer les pieces jointes des messages.

                if (tableExists(c, "message_files")) {

                    execute(
                            c,
                            "DELETE FROM message_files " +
                                    "WHERE message_id IN (" +
                                    "SELECT id FROM messages " +
                                    "WHERE sender_id=? OR receiver_id=?" +
                                    ")",
                            userId,
                            userId
                    );
                }

                if (tableExists(c, "message_attachments")) {

                    execute(
                            c,
                            "DELETE FROM message_attachments " +
                                    "WHERE message_id IN (" +
                                    "SELECT id FROM messages " +
                                    "WHERE sender_id=? OR receiver_id=?" +
                                    ")",
                            userId,
                            userId
                    );
                }

                // 5. Supprimer les messages envoyes et recus.

                execute(
                        c,
                        "DELETE FROM messages " +
                                "WHERE sender_id=? OR receiver_id=?",
                        userId,
                        userId
                );

                // 6. Supprimer les jours des plannings GPS.

                execute(
                        c,
                        "DELETE FROM schedule_days " +
                                "WHERE schedule_id IN (" +
                                "SELECT id FROM schedules WHERE user_id=?" +
                                ")",
                        userId
                );

                // 7. Supprimer les plannings.

                execute(
                        c,
                        "DELETE FROM schedules WHERE user_id=?",
                        userId
                );

                // 8. Supprimer le consentement GPS.

                if (tableExists(c, "gps_consents")) {

                    execute(
                            c,
                            "DELETE FROM gps_consents WHERE user_id=?",
                            userId
                    );
                }

                // 9. Supprimer les positions GPS.

                execute(
                        c,
                        "DELETE FROM positions WHERE user_id=?",
                        userId
                );

                // 10. Supprimer les jetons Firebase.

                if (tableExists(c, "device_tokens")) {

                    execute(
                            c,
                            "DELETE FROM device_tokens WHERE user_id=?",
                            userId
                    );
                }

                // 11. Supprimer le compte USER.

                int deleted = execute(
                        c,
                        "DELETE FROM users " +
                                "WHERE id=? AND role='USER'",
                        userId
                );

                if (deleted != 1) {

                    c.rollback();

                    return error(
                            409,
                            "Le compte n'a pas pu etre supprime."
                    );
                }

                // Valider toutes les operations ensemble.

                c.commit();

                LOG.info(
                        "ADMIN #" + currentId() +
                                " : suppression du USER #" + userId
                );

                return Response.ok(
                        Map.of(
                                "success", true,
                                "message", "Utilisateur supprime.",
                                "deletedUserId", userId,
                                "unassignedMissions", unassignedMissions
                        )
                ).build();

            } catch (Exception e) {

                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                throw e;
            }

        } catch (Exception e) {

            LOG.warning(
                    "Suppression USER impossible : " +
                            e.getClass().getSimpleName()
            );

            return error(
                    500,
                    "Suppression impossible. " +
                            "Les modifications non validees ont ete annulees."
            );
        }
    }

    private int execute(
            Connection c,
            String sql,
            long... values
    ) throws SQLException {

        try (PreparedStatement p = c.prepareStatement(sql)) {

            for (int i = 0; i < values.length; i++) {

                p.setLong(i + 1, values[i]);
            }

            return p.executeUpdate();
        }
    }

    private boolean exists(
            Connection c,
            String sql,
            long id
    ) throws SQLException {

        try (PreparedStatement p = c.prepareStatement(sql)) {

            p.setLong(1, id);

            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    private boolean tableExists(
            Connection c,
            String table
    ) throws SQLException {

        String sql =
                "SELECT 1 FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?";

        try (PreparedStatement p = c.prepareStatement(sql)) {

            p.setString(1, table);

            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    private Response error(
            int status,
            String message
    ) {

        return Response.status(status)
                .entity(
                        Map.of(
                                "success", false,
                                "message", message
                        )
                )
                .build();
    }
}