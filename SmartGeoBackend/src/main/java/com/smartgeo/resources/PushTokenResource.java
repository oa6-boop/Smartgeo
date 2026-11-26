package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

@Path("/push-tokens")
@Secured
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PushTokenResource extends BaseResource {

    public record TokenRequest(String token) {}

    @PUT
    @Path("/me")
    public Response register(TokenRequest request) {

        if (request == null ||
                request.token() == null ||
                request.token().length() < 30 ||
                request.token().length() > 512) {

            return Response.status(400)
                    .entity(Map.of(
                            "message",
                            "Token Firebase invalide."
                    ))
                    .build();
        }

        try (
                Connection connection =
                        Database.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                INSERT INTO push_tokens (
                                    token, user_id
                                )
                                VALUES (?, ?)
                                ON DUPLICATE KEY UPDATE
                                    user_id=VALUES(user_id),
                                    updated_at=CURRENT_TIMESTAMP
                                """
                        )
        ) {

            statement.setString(
                    1,
                    request.token()
            );

            statement.setLong(
                    2,
                    currentId()
            );

            statement.executeUpdate();

            return Response.ok(
                    Map.of(
                            "message",
                            "Appareil Firebase enregistré."
                    )
            ).build();

        } catch (Exception error) {
            error.printStackTrace();

            return Response.serverError()
                    .entity(Map.of(
                            "message",
                            "Enregistrement FCM impossible."
                    ))
                    .build();
        }
    }

    @DELETE
    @Path("/me")
    public Response unregister(TokenRequest request) {

        if (request == null ||
                request.token() == null) {

            return Response.status(400)
                    .entity(Map.of(
                            "message",
                            "Token obligatoire."
                    ))
                    .build();
        }

        try (
                Connection connection =
                        Database.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                DELETE FROM push_tokens
                                WHERE user_id=? AND token=?
                                """
                        )
        ) {

            statement.setLong(
                    1,
                    currentId()
            );

            statement.setString(
                    2,
                    request.token()
            );

            statement.executeUpdate();

            return Response.ok(
                    Map.of(
                            "message",
                            "Appareil désenregistré."
                    )
            ).build();

        } catch (Exception error) {
            error.printStackTrace();

            return Response.serverError()
                    .entity(Map.of(
                            "message",
                            "Désenregistrement impossible."
                    ))
                    .build();
        }
    }
}