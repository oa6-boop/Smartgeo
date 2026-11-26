
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.*;
import java.time.LocalDate;
import java.util.Map;
import java.util.logging.Logger;

@Path("/admin/mission-management")
@Secured(roles = {"ADMIN"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AdminMissionManagementResource
        extends BaseResource {

    private static final Logger LOG =
            Logger.getLogger(
                    AdminMissionManagementResource.class.getName()
            );

    public static class DatesRequest {

        public String startDate;

        public String endDate;
    }

    // MODIFIER LES DATES D'UNE MISSION

    @PUT
    @Path("/{id}/dates")
    public Response dates(
            @PathParam("id") long id,
            DatesRequest x
    ) {

        if (!admin()) {
            return forbidden();
        }

        if (id <= 0 || x == null) {

            return fail(
                    400,
                    "Donnees invalides."
            );
        }

        try {

            LocalDate start =
                    x.startDate == null ||
                            x.startDate.isBlank()
                            ? null
                            : LocalDate.parse(x.startDate);

            LocalDate end =
                    x.endDate == null ||
                            x.endDate.isBlank()
                            ? null
                            : LocalDate.parse(x.endDate);

            if (
                    start != null &&
                            end != null &&
                            end.isBefore(start)
            ) {

                return fail(
                        400,
                        "Date de fin avant le debut."
                );
            }

            try (
                    Connection c =
                            Database.getConnection();

                    PreparedStatement p =
                            c.prepareStatement(
                                    "UPDATE missions " +
                                            "SET start_date=?,end_date=? " +
                                            "WHERE id=?"
                            )
            ) {

                if (start == null) {

                    p.setNull(1, Types.DATE);

                } else {

                    p.setDate(
                            1,
                            java.sql.Date.valueOf(start)
                    );
                }

                if (end == null) {

                    p.setNull(2, Types.DATE);

                } else {

                    p.setDate(
                            2,
                            java.sql.Date.valueOf(end)
                    );
                }

                p.setLong(3, id);

                if (p.executeUpdate() == 0) {

                    return fail(
                            404,
                            "Mission introuvable."
                    );
                }

                return success(
                        "Dates mises a jour."
                );
            }

        } catch (
                java.time.format.DateTimeParseException e
        ) {

            return fail(
                    400,
                    "Format attendu : yyyy-MM-dd."
            );

        } catch (SQLException e) {

            return fail(
                    500,
                    "Erreur de base de donnees."
            );
        }
    }

    // DESAFFECTER UNE MISSION

    @PUT
    @Path("/{id}/unassign")
    public Response unassign(
            @PathParam("id") long id
    ) {

        if (!admin()) {
            return forbidden();
        }

        if (id <= 0) {

            return fail(
                    400,
                    "Identifiant invalide."
            );
        }

        try (
                Connection c =
                        Database.getConnection();

                PreparedStatement p =
                        c.prepareStatement(
                                "UPDATE missions " +
                                        "SET assigned_user_id=NULL " +
                                        "WHERE id=?"
                        )
        ) {

            p.setLong(1, id);

            if (p.executeUpdate() == 0) {

                return fail(
                        404,
                        "Mission introuvable."
                );
            }

            return success(
                    "Mission desaffectee."
            );

        } catch (SQLException e) {

            return fail(
                    500,
                    "Erreur de base de donnees."
            );
        }
    }

    // SUPPRIMER UNE MISSION - ADMIN UNIQUEMENT

    @DELETE
    @Path("/{id}")
    public Response deleteMission(
            @PathParam("id") long id
    ) {

        if (!admin()) {
            return forbidden();
        }

        if (id <= 0) {

            return fail(
                    400,
                    "Identifiant mission invalide."
            );
        }

        try (Connection c = Database.getConnection()) {

            c.setAutoCommit(false);

            try {

                // Verifier que la mission existe.

                try (
                        PreparedStatement check =
                                c.prepareStatement(
                                        "SELECT id FROM missions " +
                                                "WHERE id=? FOR UPDATE"
                                )
                ) {

                    check.setLong(1, id);

                    try (ResultSet r = check.executeQuery()) {

                        if (!r.next()) {

                            c.rollback();

                            return fail(
                                    404,
                                    "Mission introuvable."
                            );
                        }
                    }
                }

                // Supprimer les signalements associes
                // avant la suppression de la mission.

                int reportsDeleted;

                try (
                        PreparedStatement p =
                                c.prepareStatement(
                                        "DELETE FROM reports " +
                                                "WHERE mission_id=?"
                                )
                ) {

                    p.setLong(1, id);

                    reportsDeleted = p.executeUpdate();
                }

                // Supprimer la mission.

                try (
                        PreparedStatement p =
                                c.prepareStatement(
                                        "DELETE FROM missions WHERE id=?"
                                )
                ) {

                    p.setLong(1, id);

                    if (p.executeUpdate() != 1) {

                        c.rollback();

                        return fail(
                                409,
                                "Suppression de la mission impossible."
                        );
                    }
                }

                c.commit();

                LOG.info(
                        "ADMIN #" + currentId() +
                                " : suppression mission #" + id
                );

                return Response.ok(
                        Map.of(
                                "success", true,
                                "message", "Mission supprimee.",
                                "deletedMissionId", id,
                                "deletedReports", reportsDeleted
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
                    "Suppression mission impossible : " +
                            e.getClass().getSimpleName()
            );

            return fail(
                    500,
                    "Impossible de supprimer la mission."
            );
        }
    }

    private Response success(String message) {

        return Response.ok(
                Map.of(
                        "success", true,
                        "message", message
                )
        ).build();
    }

    private Response fail(
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