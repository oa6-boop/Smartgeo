package com.smartgeo.push;

import com.smartgeo.database.Database;

import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
@Startup
public class PushDispatcher {

    private static final Logger LOG =
            Logger.getLogger(
                    PushDispatcher.class.getName()
            );

    private record Event(
            long id,
            long userId,
            String type,
            long entityId,
            String title,
            String body
    ) {}

    @Schedule(
            hour = "*",
            minute = "*",
            second = "*/30",
            persistent = false
    )
    public void dispatch() {

        if (!FcmSender.configured()) {
            return;
        }

        List<Event> events = new ArrayList<>();

        try (
                Connection connection =
                        Database.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT id, user_id,
                                       event_type, entity_id,
                                       title, body
                                FROM push_events
                                WHERE delivered_at IS NULL
                                  AND attempts < 20
                                ORDER BY id ASC
                                LIMIT 25
                                """
                        );

                ResultSet result =
                        statement.executeQuery()
        ) {

            while (result.next()) {
                events.add(
                        new Event(
                                result.getLong("id"),
                                result.getLong("user_id"),
                                result.getString("event_type"),
                                result.getLong("entity_id"),
                                result.getString("title"),
                                result.getString("body")
                        )
                );
            }

        } catch (Exception error) {
            LOG.log(
                    Level.WARNING,
                    "Lecture des evenements impossible",
                    error
            );
            return;
        }

        for (Event event : events) {
            process(event);
        }
    }

    private void process(Event event) {

        List<String> tokens = new ArrayList<>();

        try (
                Connection connection =
                        Database.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT token
                                FROM push_tokens
                                WHERE user_id=?
                                """
                        )
        ) {

            statement.setLong(
                    1,
                    event.userId()
            );

            try (
                    ResultSet result =
                            statement.executeQuery()
            ) {
                while (result.next()) {
                    tokens.add(
                            result.getString("token")
                    );
                }
            }

        } catch (Exception error) {
            LOG.log(
                    Level.WARNING,
                    "Lecture des appareils impossible",
                    error
            );
            return;
        }

        // Si aucun appareil n'est inscrit,
        // l'evenement n'est plus en attente.
        if (tokens.isEmpty()) {
            finish(event.id());
            return;
        }

        boolean delivered = false;

        for (String token : tokens) {

            try {
                FcmSender.send(
                        token,
                        event.title(),
                        event.body(),
                        event.type(),
                        event.entityId()
                );

                delivered = true;

            } catch (Exception error) {
                LOG.log(
                        Level.WARNING,
                        "Notification FCM non transmise",
                        error
                );
            }
        }

        if (delivered) {
            finish(event.id());
        } else {
            incrementAttempts(event.id());
        }
    }

    private void finish(long eventId) {

        try (
                Connection connection =
                        Database.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                UPDATE push_events
                                SET delivered_at=UTC_TIMESTAMP()
                                WHERE id=?
                                """
                        )
        ) {

            statement.setLong(1, eventId);
            statement.executeUpdate();

        } catch (Exception error) {
            LOG.log(
                    Level.WARNING,
                    "Mise a jour evenement impossible",
                    error
            );
        }
    }

    private void incrementAttempts(long eventId) {

        try (
                Connection connection =
                        Database.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                UPDATE push_events
                                SET attempts=attempts+1
                                WHERE id=?
                                """
                        )
        ) {

            statement.setLong(1, eventId);
            statement.executeUpdate();

        } catch (Exception error) {
            LOG.log(
                    Level.WARNING,
                    "Mise a jour tentative impossible",
                    error
            );
        }
    }
}