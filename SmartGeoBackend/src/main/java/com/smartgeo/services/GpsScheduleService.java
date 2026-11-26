
package com.smartgeo.services;

import com.smartgeo.dao.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import java.util.Locale;
import java.util.logging.Logger;

public class GpsScheduleService {

    private static final Logger LOGGER =
            Logger.getLogger(
                    GpsScheduleService.class.getName()
            );

    private static final String DEFAULT_TIMEZONE =
            "Africa/Casablanca";

    public enum Decision {
        ALLOWED,
        ACCOUNT_INACTIVE,
        CONSENT_DISABLED,
        OUTSIDE_SCHEDULE
    }

    public Decision check(long userId) throws SQLException {

        if (userId <= 0) {
            return Decision.ACCOUNT_INACTIVE;
        }

        String accountSql = """
                SELECT status, role
                FROM users
                WHERE id = ?
                """;

        String consentSql = """
                SELECT enabled
                FROM gps_consents
                WHERE user_id = ?
                """;

        String scheduleSql = """
                SELECT
                    s.id AS schedule_id,
                    s.start_time,
                    s.end_time,
                    s.timezone,
                    d.day_of_week
                FROM schedules s
                JOIN schedule_days d
                    ON d.schedule_id = s.id
                WHERE s.user_id = ?
                  AND s.enabled = 1
                """;

        try (Connection connection =
                     DatabaseConnection.getConnection()) {

            // ==========================================
            // 1. VERIFICATION DU COMPTE
            // ==========================================

            try (PreparedStatement statement =
                         connection.prepareStatement(accountSql)) {

                statement.setLong(1, userId);

                try (ResultSet result = statement.executeQuery()) {

                    if (!result.next()) {
                        return Decision.ACCOUNT_INACTIVE;
                    }

                    String status = result.getString("status");
                    String role = result.getString("role");

                    if (!"ACTIVE".equalsIgnoreCase(status)
                            || !"USER".equalsIgnoreCase(role)) {

                        return Decision.ACCOUNT_INACTIVE;
                    }
                }
            }

            // ==========================================
            // 2. VERIFICATION DU CONSENTEMENT
            // ==========================================

            try (PreparedStatement statement =
                         connection.prepareStatement(consentSql)) {

                statement.setLong(1, userId);

                try (ResultSet result = statement.executeQuery()) {

                    if (!result.next()
                            || !result.getBoolean("enabled")) {

                        return Decision.CONSENT_DISABLED;
                    }
                }
            }

            // ==========================================
            // 3. VERIFICATION DES PLANNINGS
            // ==========================================

            Instant now = Instant.now();

            try (PreparedStatement statement =
                         connection.prepareStatement(scheduleSql)) {

                statement.setLong(1, userId);

                try (ResultSet result = statement.executeQuery()) {

                    boolean foundSchedule = false;

                    while (result.next()) {

                        foundSchedule = true;

                        long scheduleId =
                                result.getLong("schedule_id");

                        String timezone =
                                result.getString("timezone");

                        ZoneId zone;

                        try {
                            zone = ZoneId.of(
                                    timezone == null
                                            || timezone.isBlank()
                                            ? DEFAULT_TIMEZONE
                                            : timezone.trim()
                            );
                        } catch (DateTimeException exception) {

                            LOGGER.warning(
                                    "Planning GPS "
                                            + scheduleId
                                            + " : fuseau horaire invalide"
                            );

                            continue;
                        }

                        ZonedDateTime localDateTime =
                                now.atZone(zone);

                        LocalTime currentTime =
                                localDateTime.toLocalTime();

                        DayOfWeek currentDay =
                                localDateTime.getDayOfWeek();

                        Time sqlStart =
                                result.getTime("start_time");

                        Time sqlEnd =
                                result.getTime("end_time");

                        if (sqlStart == null || sqlEnd == null) {
                            LOGGER.warning(
                                    "Planning GPS "
                                            + scheduleId
                                            + " : heures manquantes"
                            );
                            continue;
                        }

                        LocalTime startTime =
                                sqlStart.toLocalTime();

                        LocalTime endTime =
                                sqlEnd.toLocalTime();

                        String configuredDay =
                                result.getString("day_of_week");

                        if (configuredDay == null
                                || configuredDay.isBlank()) {
                            continue;
                        }

                        configuredDay =
                                configuredDay.trim()
                                        .toUpperCase(Locale.ROOT);

                        // ==================================
                        // VERIFICATION DU JOUR
                        // ==================================

                        boolean correctDay =
                                currentDay.name()
                                        .equals(configuredDay);

                        // ==================================
                        // VERIFICATION DE L'HEURE
                        // ==================================

                        boolean correctTime;

                        if (startTime.isBefore(endTime)) {

                            correctTime =
                                    !currentTime.isBefore(startTime)
                                            && currentTime.isBefore(endTime);

                        } else if (startTime.isAfter(endTime)) {

                            // Planning traversant minuit :
                            // Exemple : lundi 22:00 -> mardi 02:00
                            // Le jour configure est le jour
                            // de debut du planning.

                            if (!currentTime.isBefore(startTime)) {

                                correctTime = correctDay;

                            } else if (currentTime.isBefore(endTime)) {

                                DayOfWeek previousDay =
                                        currentDay.minus(1);

                                correctTime =
                                        previousDay.name()
                                                .equals(configuredDay);

                            } else {

                                correctTime = false;
                            }

                            if (correctTime) {
                                LOGGER.info(
                                        "GPS autorise pour userId="
                                                + userId
                                                + " via planning "
                                                + scheduleId
                                );

                                return Decision.ALLOWED;
                            }

                            continue;

                        } else {

                            // Heure de debut = heure de fin :
                            // planning non valide.
                            correctTime = false;
                        }

                        // ==================================
                        // DIAGNOSTIC DU PLANNING
                        // ==================================

                        LOGGER.info(
                                "GPS planning check : userId="
                                        + userId
                                        + ", scheduleId="
                                        + scheduleId
                                        + ", timezone="
                                        + zone
                                        + ", localDay="
                                        + currentDay
                                        + ", configuredDay="
                                        + configuredDay
                                        + ", localTime="
                                        + currentTime
                                        + ", start="
                                        + startTime
                                        + ", end="
                                        + endTime
                                        + ", correctDay="
                                        + correctDay
                                        + ", correctTime="
                                        + correctTime
                        );

                        if (correctDay && correctTime) {

                            LOGGER.info(
                                    "GPS autorise pour userId="
                                            + userId
                                            + " via planning "
                                            + scheduleId
                            );

                            return Decision.ALLOWED;
                        }
                    }

                    if (!foundSchedule) {
                        LOGGER.info(
                                "Aucun planning GPS actif pour userId="
                                        + userId
                        );
                    }
                }
            }

            return Decision.OUTSIDE_SCHEDULE;
        }
    }
}
