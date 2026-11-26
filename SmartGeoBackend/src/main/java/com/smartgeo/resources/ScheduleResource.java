
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.sql.*;
import java.util.*;

@Path("/schedules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class ScheduleResource extends BaseResource {

    public record ScheduleInput(
            String startTime,
            String endTime,
            Integer frequencySeconds,
            String timezone,
            Boolean enabled,
            List<String> days
    ) {}

    // CONSULTER TOUS LES PLANNINGS - ADMIN

    @GET
    public Response list() {

        if (!admin()) {
            return forbidden();
        }

        try (
                Connection c = Database.getConnection();

                PreparedStatement p = c.prepareStatement(
                        "SELECT * FROM schedules ORDER BY id DESC"
                );

                ResultSet r = p.executeQuery()
        ) {

            List<Map<String, Object>> result =
                    new ArrayList<>();

            while (r.next()) {
                result.add(row(r));
            }

            return Response.ok(result).build();

        } catch (Exception e) {

            return error(
                    500,
                    "Lecture des plannings impossible."
            );
        }
    }

    // CONSULTER MES PLANNINGS

    @GET
    @Path("/me")
    public Response mine() {

        return byUser(currentId());
    }

    // CONSULTER LES PLANNINGS D'UN UTILISATEUR

    @GET
    @Path("/user/{id}")
    public Response user(
            @PathParam("id") long id
    ) {

        if (!admin() && id != currentId()) {
            return forbidden();
        }

        return byUser(id);
    }

    private Response byUser(long id) {

        try (
                Connection c = Database.getConnection();

                PreparedStatement p = c.prepareStatement(
                        "SELECT * FROM schedules " +
                                "WHERE user_id=? ORDER BY id DESC"
                )
        ) {

            p.setLong(1, id);

            try (ResultSet r = p.executeQuery()) {

                List<Map<String, Object>> result =
                        new ArrayList<>();

                while (r.next()) {
                    result.add(row(r));
                }

                return Response.ok(result).build();
            }

        } catch (Exception e) {

            return error(
                    500,
                    "Lecture du planning impossible."
            );
        }
    }

    // CREER UN PLANNING

    @POST
    public Response create(ScheduleInput x) {

        if (
                x == null ||
                        x.startTime() == null ||
                        x.endTime() == null ||
                        x.frequencySeconds() == null ||
                        x.frequencySeconds() < 10
        ) {

            return error(
                    400,
                    "Heures et frequence >= 10 secondes obligatoires."
            );
        }

        try {

            long id = Database.insert(

                    "INSERT INTO schedules " +
                            "(user_id,start_time,end_time," +
                            "frequency_seconds,timezone,enabled) " +
                            "VALUES(?,?,?,?,?,?)",

                    p -> {

                        p.setLong(1, currentId());

                        p.setString(
                                2,
                                x.startTime()
                        );

                        p.setString(
                                3,
                                x.endTime()
                        );

                        p.setInt(
                                4,
                                x.frequencySeconds()
                        );

                        p.setString(
                                5,
                                x.timezone() == null
                                        ? "Africa/Casablanca"
                                        : x.timezone()
                        );

                        p.setBoolean(
                                6,
                                x.enabled() == null ||
                                        x.enabled()
                        );
                    }
            );

            if (x.days() != null) {

                for (String day : x.days()) {

                    Database.update(

                            "INSERT IGNORE INTO schedule_days " +
                                    "(schedule_id,day_of_week) VALUES(?,?)",

                            p -> {

                                p.setLong(1, id);

                                p.setString(
                                        2,
                                        day.toUpperCase(Locale.ROOT)
                                );
                            }
                    );
                }
            }

            return Response.status(201)
                    .entity(Map.of("id", id))
                    .build();

        } catch (Exception e) {

            return error(
                    500,
                    "Creation planning impossible. Verifiez les jours."
            );
        }
    }

    // MODIFIER UN PLANNING

    @PUT
    @Path("/{id}")
    public Response update(
            @PathParam("id") long id,
            ScheduleInput x
    ) {

        if (
                id <= 0 ||
                        x == null ||
                        x.startTime() == null ||
                        x.endTime() == null ||
                        x.frequencySeconds() == null ||
                        x.frequencySeconds() < 10
        ) {

            return error(
                    400,
                    "Donnees du planning invalides."
            );
        }

        try {

            int n = Database.update(

                    "UPDATE schedules SET " +
                            "start_time=?,end_time=?," +
                            "frequency_seconds=?,timezone=?,enabled=? " +
                            "WHERE id=? AND " +
                            "(user_id=? OR ?='ADMIN')",

                    p -> {

                        p.setString(
                                1,
                                x.startTime()
                        );

                        p.setString(
                                2,
                                x.endTime()
                        );

                        p.setInt(
                                3,
                                x.frequencySeconds()
                        );

                        p.setString(
                                4,
                                x.timezone() == null
                                        ? "Africa/Casablanca"
                                        : x.timezone()
                        );

                        p.setBoolean(
                                5,
                                x.enabled() == null ||
                                        x.enabled()
                        );

                        p.setLong(6, id);

                        p.setLong(7, currentId());

                        p.setString(8, currentRole());
                    }
            );

            if (n == 0) {

                return error(
                        404,
                        "Planning introuvable."
                );
            }

            return Response.ok(
                    Map.of(
                            "success", true,
                            "message", "Planning modifie."
                    )
            ).build();

        } catch (Exception e) {

            return error(
                    500,
                    "Modification du planning impossible."
            );
        }
    }

    // SUPPRIMER UN PLANNING - ADMIN UNIQUEMENT

    @DELETE
    @Path("/{id}")
    @Secured(roles = {"ADMIN"})
    public Response delete(
            @PathParam("id") long id
    ) {

        // Protection supplementaire cote serveur.

        if (!admin()) {
            return forbidden();
        }

        if (id <= 0) {

            return error(
                    400,
                    "Identifiant planning invalide."
            );
        }

        try (Connection c = Database.getConnection()) {

            c.setAutoCommit(false);

            try {

                // Verifier et verrouiller le planning.

                try (
                        PreparedStatement p =
                                c.prepareStatement(
                                        "SELECT id FROM schedules " +
                                                "WHERE id=? FOR UPDATE"
                                )
                ) {

                    p.setLong(1, id);

                    try (ResultSet r = p.executeQuery()) {

                        if (!r.next()) {

                            c.rollback();

                            return error(
                                    404,
                                    "Planning introuvable."
                            );
                        }
                    }
                }

                // Supprimer les jours du planning.

                try (
                        PreparedStatement p =
                                c.prepareStatement(
                                        "DELETE FROM schedule_days " +
                                                "WHERE schedule_id=?"
                                )
                ) {

                    p.setLong(1, id);

                    p.executeUpdate();
                }

                // Supprimer le planning principal.

                try (
                        PreparedStatement p =
                                c.prepareStatement(
                                        "DELETE FROM schedules " +
                                                "WHERE id=?"
                                )
                ) {

                    p.setLong(1, id);

                    if (p.executeUpdate() != 1) {

                        c.rollback();

                        return error(
                                409,
                                "Suppression du planning impossible."
                        );
                    }
                }

                c.commit();

                return Response.ok(
                        Map.of(
                                "success", true,
                                "message", "Planning supprime.",
                                "deletedScheduleId", id
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

            return error(
                    500,
                    "Suppression du planning impossible."
            );
        }
    }

    // CONVERSION DES RESULTATS MYSQL

    private Map<String, Object> row(
            ResultSet r
    ) throws SQLException {

        Map<String, Object> data =
                new LinkedHashMap<>();

        for (String key : List.of(

                "id",
                "user_id",
                "start_time",
                "end_time",
                "frequency_seconds",
                "timezone",
                "enabled"

        )) {

            data.put(
                    key,
                    r.getObject(key)
            );
        }

        return data;
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