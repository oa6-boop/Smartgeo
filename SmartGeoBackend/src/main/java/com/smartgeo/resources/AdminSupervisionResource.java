
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;
import com.smartgeo.services.GpsScheduleService;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;

@Path("/admin/supervision")
@Secured(roles = {"ADMIN"})
@Produces(MediaType.APPLICATION_JSON)
public class AdminSupervisionResource extends BaseResource {

    private static final Logger LOG =
            Logger.getLogger(
                    AdminSupervisionResource.class.getName()
            );

    private final GpsScheduleService gpsService =
            new GpsScheduleService();

    @GET
    public Response supervision() {

        if (!admin()) {
            return forbidden();
        }

        List<Map<String, Object>> agents =
                new ArrayList<>();

        Map<String, Object> statistics =
                new LinkedHashMap<>();

        Map<String, Long> activity =
                new LinkedHashMap<>();

        Instant now = Instant.now();

        LocalDate today =
                LocalDate.now(ZoneOffset.UTC);

        for (int i = 6; i >= 0; i--) {
            activity.put(
                    today.minusDays(i).toString(),
                    0L
            );
        }

        String sql = """
                SELECT
                    u.id,
                    u.full_name,
                    u.username,
                    u.status,
                    COALESCE(g.enabled, 0) AS consent,
                    p.latitude,
                    p.longitude,
                    p.accuracy,
                    p.speed,
                    p.battery_level,
                    p.recorded_at
                FROM users u
                LEFT JOIN gps_consents g
                    ON g.user_id = u.id
                LEFT JOIN positions p
                    ON p.id = (
                        SELECT p2.id
                        FROM positions p2
                        WHERE p2.user_id = u.id
                        ORDER BY
                            p2.recorded_at DESC,
                            p2.id DESC
                        LIMIT 1
                    )
                WHERE u.role = 'USER'
                ORDER BY u.full_name, u.id
                """;

        try (Connection c = Database.getConnection()) {

            try (
                    PreparedStatement p =
                            c.prepareStatement(sql);

                    ResultSet r = p.executeQuery()
            ) {

                while (r.next()) {

                    Map<String, Object> item =
                            new LinkedHashMap<>();

                    long id = r.getLong("id");

                    boolean consent =
                            r.getBoolean("consent");

                    String accountStatus =
                            r.getString("status");

                    item.put("id", id);

                    item.put(
                            "fullName",
                            r.getString("full_name")
                    );

                    item.put(
                            "username",
                            r.getString("username")
                    );

                    item.put(
                            "accountStatus",
                            accountStatus
                    );

                    item.put("consent", consent);

                    boolean visible =
                            consent &&
                                    "ACTIVE".equals(accountStatus);

                    for (String key : List.of(
                            "latitude",
                            "longitude",
                            "accuracy",
                            "speed",
                            "battery_level"
                    )) {

                        item.put(
                                key,
                                visible
                                        ? r.getObject(key)
                                        : null
                        );
                    }

                    Timestamp recorded =
                            r.getTimestamp(
                                    "recorded_at",
                                    utcCalendar()
                            );

                    item.put(
                            "recorded_at",
                            visible && recorded != null
                                    ? recorded.toInstant().toString()
                                    : null
                    );

                    agents.add(item);
                }
            }

            statistics.put(
                    "missions_in_progress",
                    count(
                            c,
                            "SELECT COUNT(*) FROM missions " +
                                    "WHERE status='IN_PROGRESS'"
                    )
            );

            statistics.put(
                    "missions_completed",
                    count(
                            c,
                            "SELECT COUNT(*) FROM missions " +
                                    "WHERE status='COMPLETED'"
                    )
            );

            statistics.put(
                    "pending_reports",
                    count(
                            c,
                            "SELECT COUNT(*) FROM reports " +
                                    "WHERE status='SUBMITTED'"
                    )
            );

            try (
                    PreparedStatement p =
                            c.prepareStatement("""
                                SELECT COUNT(*)
                                FROM messages
                                WHERE receiver_id=?
                                  AND read_at IS NULL
                                """)
            ) {

                p.setLong(1, currentId());

                try (ResultSet r = p.executeQuery()) {

                    r.next();

                    statistics.put(
                            "unread_messages",
                            r.getLong(1)
                    );
                }
            }

            statistics.put(
                    "pending_requests",
                    count(
                            c,
                            "SELECT COUNT(*) FROM location_requests " +
                                    "WHERE status='PENDING'"
                    )
            );

            // Nombre réel de relevés GPS par jour.
            // Il ne s'agit pas d'un nombre de sessions.

            try (
                    PreparedStatement p =
                            c.prepareStatement("""
                                SELECT DATE(recorded_at) AS day,
                                       COUNT(*) AS total
                                FROM positions
                                WHERE recorded_at >= ?
                                  AND recorded_at < ?
                                GROUP BY DATE(recorded_at)
                                ORDER BY day
                                """)
            ) {

                p.setTimestamp(
                        1,
                        Timestamp.from(
                                today.minusDays(6)
                                        .atStartOfDay(ZoneOffset.UTC)
                                        .toInstant()
                        ),
                        utcCalendar()
                );

                p.setTimestamp(
                        2,
                        Timestamp.from(
                                today.plusDays(1)
                                        .atStartOfDay(ZoneOffset.UTC)
                                        .toInstant()
                        ),
                        utcCalendar()
                );

                try (ResultSet r = p.executeQuery()) {

                    while (r.next()) {

                        String day =
                                r.getString("day");

                        if (activity.containsKey(day)) {

                            activity.put(
                                    day,
                                    r.getLong("total")
                            );
                        }
                    }
                }
            }

        } catch (SQLException e) {

            LOG.warning("Lecture supervision impossible.");

            return Response.serverError()
                    .entity(Map.of(
                            "success", false,
                            "message", "Supervision indisponible."
                    ))
                    .build();
        }

        // Verifier le planning et le consentement
        // avec le service GPS existant.

        int recent = 0;
        int activeAccounts = 0;
        int withPosition = 0;

        for (Map<String, Object> agent : agents) {

            long id =
                    ((Number) agent.get("id")).longValue();

            if ("ACTIVE".equals(
                    agent.get("accountStatus")
            )) {
                activeAccounts++;
            }

            String decision;

            try {

                decision = gpsService.check(id).name();

            } catch (Exception e) {

                decision = "UNKNOWN";
            }

            agent.put("gpsStatus", decision);

            // Si le consentement ou le compte a changé
            // depuis la lecture, masquer la position.

            if (
                    "CONSENT_DISABLED".equals(decision) ||
                            "ACCOUNT_INACTIVE".equals(decision) ||
                            "UNKNOWN".equals(decision)
            ) {

                for (String key : List.of(
                        "latitude",
                        "longitude",
                        "accuracy",
                        "speed",
                        "battery_level",
                        "recorded_at"
                )) {
                    agent.put(key, null);
                }
            }

            String date =
                    (String) agent.get("recorded_at");

            boolean isRecent = false;

            if (date != null) {

                withPosition++;

                long age = Duration.between(
                        Instant.parse(date),
                        now
                ).getSeconds();

                agent.put(
                        "ageSeconds",
                        Math.max(0, age)
                );

                isRecent =
                        age >= -5 &&
                                age <= 120;

            } else {

                agent.put("ageSeconds", null);
            }

            agent.put("recent", isRecent);

            if (isRecent) {
                recent++;
            }
        }

        statistics.put(
                "agents_total",
                agents.size()
        );

        statistics.put(
                "agents_active",
                activeAccounts
        );

        statistics.put(
                "agents_recent",
                recent
        );

        statistics.put(
                "agents_with_position",
                withPosition
        );

        List<Map<String, Object>> days =
                new ArrayList<>();

        activity.forEach((date, total) -> {

            days.add(Map.of(
                    "date", date,
                    "total", total
            ));
        });

        return Response.ok(Map.of(
                "agents", agents,
                "statistics", statistics,
                "activity", days,
                "generatedAt", now.toString()
        )).build();
    }

    private long count(
            Connection c,
            String sql
    ) throws SQLException {

        try (
                PreparedStatement p =
                        c.prepareStatement(sql);

                ResultSet r = p.executeQuery()
        ) {

            return r.next()
                    ? r.getLong(1)
                    : 0L;
        }
    }

    private Calendar utcCalendar() {

        return Calendar.getInstance(
                TimeZone.getTimeZone("UTC")
        );
    }
}
