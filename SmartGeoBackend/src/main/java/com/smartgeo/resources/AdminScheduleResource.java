
package com.smartgeo.resources;

import com.smartgeo.dao.DatabaseConnection;
import com.smartgeo.dao.UserDAO;
import com.smartgeo.entities.User;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.sql.*;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

@Path("/admin/schedules")
@Secured(roles = {"ADMIN"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AdminScheduleResource {

    private final UserDAO userDAO = new UserDAO();

    @Context
    private SecurityContext securityContext;

    public static class ScheduleRequest {
        public Long userId;
        public String startTime;
        public String endTime;
        public Integer frequencySeconds;
        public String timezone;
        public Boolean enabled;
        public List<String> days;
    }

    private static final Set<String> VALID_DAYS = Set.of(
            "MONDAY", "TUESDAY", "WEDNESDAY",
            "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
    );

    private Response error(int status, String message) {
        return Response.status(status)
                .entity(Map.of(
                        "success", false,
                        "message", message
                ))
                .build();
    }

    private boolean authorized() throws Exception {
        if (securityContext == null ||
                securityContext.getUserPrincipal() == null) {
            return false;
        }

        long adminId = Long.parseLong(
                securityContext.getUserPrincipal().getName()
        );

        User admin = userDAO.findById(adminId);

        return admin != null
                && "ADMIN".equals(admin.getRole())
                && "ACTIVE".equals(admin.getStatus());
    }

    private String validate(ScheduleRequest x) {
        if (x == null) {
            return "Donnees manquantes.";
        }

        if (x.startTime == null || x.endTime == null) {
            return "Heures obligatoires.";
        }

        try {
            LocalTime start = LocalTime.parse(x.startTime);
            LocalTime end = LocalTime.parse(x.endTime);

            if (!start.isBefore(end)) {
                return "L'heure de debut doit preceder la fin.";
            }
        } catch (Exception e) {
            return "Format horaire attendu : HH:mm:ss.";
        }

        if (x.frequencySeconds == null ||
                x.frequencySeconds < 30 ||
                x.frequencySeconds > 3600) {
            return "Frequence autorisee : 30 a 3600 secondes.";
        }

        String timezone = x.timezone == null
                ? "Africa/Casablanca" : x.timezone;

        try {
            ZoneId.of(timezone);
        } catch (Exception e) {
            return "Fuseau horaire invalide.";
        }

        if (x.days == null || x.days.isEmpty()) {
            return "Selectionnez au moins un jour.";
        }

        for (String day : x.days) {
            if (day == null ||
                    !VALID_DAYS.contains(day.toUpperCase(Locale.ROOT))) {
                return "Jour invalide : " + day;
            }
        }

        return null;
    }

    // POST /api/admin/schedules
    @POST
    public Response create(ScheduleRequest x) {
        try {
            if (!authorized()) {
                return error(403, "Acces administrateur interdit.");
            }

            String validation = validate(x);

            if (validation != null) {
                return error(400, validation);
            }

            if (x.userId == null || x.userId <= 0) {
                return error(400, "Utilisateur obligatoire.");
            }

            User user = userDAO.findById(x.userId);

            if (user == null) {
                return error(404, "Utilisateur introuvable.");
            }

            if (!"USER".equals(user.getRole()) ||
                    !"ACTIVE".equals(user.getStatus())) {
                return error(409, "Utilisateur non actif ou role invalide.");
            }

            try (Connection c = DatabaseConnection.getConnection()) {
                c.setAutoCommit(false);

                try {
                    long scheduleId;

                    String sql =
                            "INSERT INTO schedules " +
                                    "(user_id,start_time,end_time," +
                                    "frequency_seconds,timezone,enabled) " +
                                    "VALUES(?,?,?,?,?,?)";

                    try (PreparedStatement p = c.prepareStatement(
                            sql, Statement.RETURN_GENERATED_KEYS
                    )) {
                        p.setLong(1, x.userId);
                        p.setTime(2, Time.valueOf(x.startTime));
                        p.setTime(3, Time.valueOf(x.endTime));
                        p.setInt(4, x.frequencySeconds);
                        p.setString(5, x.timezone == null
                                ? "Africa/Casablanca" : x.timezone);
                        p.setBoolean(6,
                                x.enabled == null || x.enabled);

                        p.executeUpdate();

                        try (ResultSet r = p.getGeneratedKeys()) {
                            if (!r.next()) {
                                throw new SQLException("ID planning absent.");
                            }
                            scheduleId = r.getLong(1);
                        }
                    }

                    insertDays(c, scheduleId, x.days);

                    c.commit();

                    return Response.status(201)
                            .entity(Map.of(
                                    "success", true,
                                    "message", "Planning cree.",
                                    "id", scheduleId,
                                    "userId", x.userId
                            ))
                            .build();

                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Creation du planning impossible.");
        }
    }

    // PUT /api/admin/schedules/{id}
    @PUT
    @Path("/{id}")
    public Response update(
            @PathParam("id") long id,
            ScheduleRequest x
    ) {
        try {
            if (!authorized()) {
                return error(403, "Acces administrateur interdit.");
            }

            String validation = validate(x);

            if (validation != null) {
                return error(400, validation);
            }

            try (Connection c = DatabaseConnection.getConnection()) {
                c.setAutoCommit(false);

                try {
                    String sql =
                            "UPDATE schedules SET " +
                                    "start_time=?,end_time=?," +
                                    "frequency_seconds=?,timezone=?,enabled=? " +
                                    "WHERE id=?";

                    int updated;

                    try (PreparedStatement p = c.prepareStatement(sql)) {
                        p.setTime(1, Time.valueOf(x.startTime));
                        p.setTime(2, Time.valueOf(x.endTime));
                        p.setInt(3, x.frequencySeconds);
                        p.setString(4, x.timezone == null
                                ? "Africa/Casablanca" : x.timezone);
                        p.setBoolean(5,
                                x.enabled == null || x.enabled);
                        p.setLong(6, id);

                        updated = p.executeUpdate();
                    }

                    if (updated == 0) {
                        c.rollback();
                        return error(404, "Planning introuvable.");
                    }

                    try (PreparedStatement p = c.prepareStatement(
                            "DELETE FROM schedule_days WHERE schedule_id=?"
                    )) {
                        p.setLong(1, id);
                        p.executeUpdate();
                    }

                    insertDays(c, id, x.days);

                    c.commit();

                    return Response.ok(Map.of(
                            "success", true,
                            "message", "Planning modifie.",
                            "id", id
                    )).build();

                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Modification du planning impossible.");
        }
    }

    private void insertDays(
            Connection c,
            long scheduleId,
            List<String> days
    ) throws SQLException {

        String sql =
                "INSERT INTO schedule_days " +
                        "(schedule_id,day_of_week) VALUES(?,?)";

        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (String day : new LinkedHashSet<>(days)) {
                p.setLong(1, scheduleId);
                p.setString(2, day.toUpperCase(Locale.ROOT));
                p.addBatch();
            }

            p.executeBatch();
        }
    }
}
