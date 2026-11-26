
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;
import com.smartgeo.services.GpsScheduleService;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

@Path("/positions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class PositionResource extends BaseResource {

    private final GpsScheduleService gpsScheduleService =
            new GpsScheduleService();

    public record NewPosition(
            Double latitude,
            Double longitude,
            Double accuracy,
            Double speed,
            Double bearing,
            Integer batteryLevel,
            String recordedAt,
            String clientPositionId
    ) {}

    // =====================================================
    // 1. ENREGISTRER UNE POSITION GPS
    // =====================================================

    @POST
    public Response add(NewPosition x) {

        // Verifier l'authentification.
        long userId = currentId();

        if (userId <= 0) {
            return Response.status(401)
                    .entity(Map.of(
                            "error", "Utilisateur non authentifie"
                    ))
                    .build();
        }

        // Verifier les coordonnees et mesures GPS.
        if (x == null
                || x.latitude() == null
                || x.longitude() == null
                || !Double.isFinite(x.latitude())
                || !Double.isFinite(x.longitude())
                || x.latitude() < -90
                || x.latitude() > 90
                || x.longitude() < -180
                || x.longitude() > 180
                || (x.accuracy() != null && x.accuracy() <= 0)
                || !validOptional(x.accuracy(), 0, Double.MAX_VALUE)
                || !validOptional(x.speed(), 0, Double.MAX_VALUE)
                || !validOptional(x.bearing(), 0, 360)
                || (x.batteryLevel() != null
                && (x.batteryLevel() < 0
                || x.batteryLevel() > 100))) {

            return badRequest(
                    "Coordonnees ou mesures GPS invalides"
            );
        }

        if (x.recordedAt() == null
                || x.clientPositionId() == null) {

            return badRequest(
                    "recordedAt et clientPositionId obligatoires"
            );
        }

        final Instant recorded;
        final String uuid;

        try {
            recorded = Instant.parse(x.recordedAt());

            uuid = UUID.fromString(
                    x.clientPositionId()
            ).toString();

        } catch (DateTimeParseException
                 | IllegalArgumentException e) {

            return badRequest(
                    "Date UTC ISO-8601 ou UUID invalide"
            );
        }

        // Refuser les dates trop avancees.
        if (recorded.isAfter(
                Instant.now().plusSeconds(300))) {

            return badRequest("Date GPS dans le futur");
        }

        // =================================================
        // 2. VERIFIER CONSENTEMENT ET PLANNING GPS
        // =================================================

        try {
            GpsScheduleService.Decision decision =
                    gpsScheduleService.check(userId);

            if (decision !=
                    GpsScheduleService.Decision.ALLOWED) {

                String message = switch (decision) {

                    case ACCOUNT_INACTIVE ->
                            "Compte utilisateur inactif ou non autorise.";

                    case CONSENT_DISABLED ->
                            "Le partage GPS est desactive.";

                    case OUTSIDE_SCHEDULE ->
                            "Position refusee : hors planning GPS.";

                    case ALLOWED ->
                            "Position autorisee.";
                };

                return Response.status(403)
                        .entity(Map.of(
                                "success", false,
                                "message", message
                        ))
                        .build();
            }

        } catch (Exception e) {
            e.printStackTrace();

            return Response.serverError()
                    .entity(Map.of(
                            "success", false,
                            "message",
                            "Verification des autorisations GPS impossible."
                    ))
                    .build();
        }

        // =================================================
        // 3. ENREGISTRER LA POSITION DANS MYSQL
        // =================================================

        String sql = """
                INSERT INTO positions (
                    user_id,
                    latitude,
                    longitude,
                    accuracy,
                    speed,
                    bearing,
                    battery_level,
                    recorded_at,
                    received_at,
                    client_position_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                        UTC_TIMESTAMP(), ?)
                ON DUPLICATE KEY UPDATE
                    id = LAST_INSERT_ID(id)
                """;

        try (
                Connection c = Database.getConnection();
                PreparedStatement p = c.prepareStatement(
                        sql,
                        Statement.RETURN_GENERATED_KEYS
                )
        ) {

            p.setLong(1, userId);
            p.setDouble(2, x.latitude());
            p.setDouble(3, x.longitude());

            p.setObject(4, x.accuracy());
            p.setObject(5, x.speed());
            p.setObject(6, x.bearing());
            p.setObject(7, x.batteryLevel());

            p.setTimestamp(
                    8,
                    Timestamp.from(recorded), utcCalendar()
            );

            p.setString(9, uuid);

            int affected = p.executeUpdate();

            long id = -1;

            try (ResultSet keys = p.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }

            // Recuperer l'identifiant si necessaire.
            if (id <= 0) {

                String findSql = """
                        SELECT id
                        FROM positions
                        WHERE user_id = ?
                          AND client_position_id = ?
                        """;

                try (
                        PreparedStatement find =
                                c.prepareStatement(findSql)
                ) {

                    find.setLong(1, userId);
                    find.setString(2, uuid);

                    try (ResultSet rs = find.executeQuery()) {
                        if (rs.next()) {
                            id = rs.getLong(1);
                        }
                    }
                }
            }

            boolean created = affected == 1;

            return Response.status(
                    created ? 201 : 200
            ).entity(Map.of(
                    "id", id,
                    "message", created
                            ? "Position enregistree"
                            : "Position deja recue"
            )).build();

        } catch (SQLException e) {
            e.printStackTrace();

            return Response.serverError()
                    .entity(Map.of(
                            "error",
                            "Enregistrement GPS impossible"
                    ))
                    .build();
        }
    }

    // =====================================================
    // 4. VALIDATION DES MESURES FACULTATIVES
    // =====================================================

    private boolean validOptional(
            Double value,
            double min,
            double max
    ) {
        return value == null
                || (Double.isFinite(value)
                && value >= min
                && value <= max);
    }

    private Response badRequest(String message) {

        return Response.status(400)
                .entity(Map.of("error", message))
                .build();
    }

    // =====================================================
    // 5. DERNIERE POSITION DE L'UTILISATEUR CONNECTE
    // =====================================================

    @GET
    @Path("/me/latest")
    public Response latest() {
        return latestFor(currentId());
    }

    // =====================================================
    // 6. DERNIERE POSITION D'UN UTILISATEUR
    // =====================================================

    @GET
    @Path("/user/{id}/latest")
    public Response latestUser(
            @PathParam("id") long id
    ) {

        if (!admin() && id != currentId()) {
            return forbidden();
        }

        return latestFor(id);
    }

    private Response latestFor(long id) {

        if (id <= 0) {
            return badRequest("Utilisateur invalide");
        }

        String sql = """
                SELECT *
                FROM positions
                WHERE user_id = ?
                ORDER BY recorded_at DESC, id DESC
                LIMIT 1
                """;

        try (
                Connection c = Database.getConnection();
                PreparedStatement p = c.prepareStatement(sql)
        ) {

            p.setLong(1, id);

            try (ResultSet r = p.executeQuery()) {

                if (!r.next()) {
                    return Response.status(404)
                            .entity(Map.of(
                                    "error",
                                    "Aucune position"
                            ))
                            .build();
                }

                return Response.ok(row(r)).build();
            }

        } catch (SQLException e) {
            e.printStackTrace();

            return Response.serverError()
                    .entity(Map.of(
                            "error",
                            "Lecture GPS impossible"
                    ))
                    .build();
        }
    }

    // =====================================================
    // 7. HISTORIQUE DE L'UTILISATEUR CONNECTE
    // =====================================================

    @GET
    @Path("/me/history")
    public Response history(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("limit")
            @DefaultValue("500") int limit
    ) {

        return historyFor(
                currentId(),
                from,
                to,
                limit
        );
    }

    // =====================================================
    // 8. HISTORIQUE D'UN UTILISATEUR
    // =====================================================

    @GET
    @Path("/user/{id}/history")
    public Response userHistory(
            @PathParam("id") long id,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("limit")
            @DefaultValue("500") int limit
    ) {

        if (!admin() && id != currentId()) {
            return forbidden();
        }

        return historyFor(id, from, to, limit);
    }

    private Response historyFor(
            long id,
            String from,
            String to,
            int limit
    ) {

        if (id <= 0) {
            return badRequest("Utilisateur invalide");
        }

        final Timestamp start;
        final Timestamp end;

        try {

            start = from == null || from.isBlank()
                    ? null
                    : Timestamp.from(Instant.parse(from));

            end = to == null || to.isBlank()
                    ? null
                    : Timestamp.from(Instant.parse(to));

        } catch (DateTimeParseException e) {

            return badRequest(
                    "from et to doivent etre des dates UTC ISO-8601"
            );
        }

        if (start != null
                && end != null
                && start.after(end)) {

            return badRequest("Periode invalide");
        }

        String sql = """
                SELECT *
                FROM positions
                WHERE user_id = ?
                  AND (? IS NULL OR recorded_at >= ?)
                  AND (? IS NULL OR recorded_at <= ?)
                ORDER BY recorded_at DESC, id DESC
                LIMIT ?
                """;

        try (
                Connection c = Database.getConnection();
                PreparedStatement p = c.prepareStatement(sql)
        ) {

            p.setLong(1, id);

            p.setTimestamp(2, start, utcCalendar());
            p.setTimestamp(3, start, utcCalendar());
            p.setTimestamp(4, end, utcCalendar());
            p.setTimestamp(5, end, utcCalendar());

            p.setInt(
                    6,
                    Math.max(1, Math.min(limit, 2000))
            );

            try (ResultSet r = p.executeQuery()) {

                List<Map<String, Object>> result =
                        new ArrayList<>();

                while (r.next()) {
                    result.add(row(r));
                }

                return Response.ok(result).build();
            }

        } catch (SQLException e) {
            e.printStackTrace();

            return Response.serverError()
                    .entity(Map.of(
                            "error",
                            "Historique GPS indisponible"
                    ))
                    .build();
        }
    }

    // =====================================================
    // 9. CONVERTIR UNE POSITION SQL EN JSON
    // =====================================================

    private Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private Map<String, Object> row(ResultSet r)
            throws SQLException {

        Map<String, Object> data =
                new LinkedHashMap<>();

        for (String key : List.of(
                "id",
                "user_id",
                "latitude",
                "longitude",
                "accuracy",
                "speed",
                "bearing",
                "battery_level",
                "recorded_at",
                "received_at",
                "client_position_id"
        )) {

            if ("recorded_at".equals(key) || "received_at".equals(key)) {
                Timestamp value = r.getTimestamp(key, utcCalendar());
                data.put(key, value == null ? null : value.toInstant().toString());
            } else {
                data.put(key, r.getObject(key));
            }
        }

        return data;
    }
}
