
package com.smartgeo.resources;

import com.smartgeo.dao.DatabaseConnection;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.*;
import java.util.Map;

@Path("/gps-consent")
@Secured
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class GpsConsentResource extends BaseResource {

    public static class ConsentRequest {
        public Boolean enabled;
    }

    // L'utilisateur consulte son propre consentement.
    @GET
    @Path("/me")
    public Response getConsent() {

        String sql =
                "SELECT enabled FROM gps_consents WHERE user_id=?";

        try (
                Connection c = DatabaseConnection.getConnection();
                PreparedStatement p = c.prepareStatement(sql)
        ) {
            p.setLong(1, currentId());

            try (ResultSet r = p.executeQuery()) {
                boolean enabled =
                        r.next() && r.getBoolean("enabled");

                return Response.ok(Map.of(
                        "userId", currentId(),
                        "enabled", enabled
                )).build();
            }

        } catch (SQLException e) {
            e.printStackTrace();
            return Response.serverError().build();
        }
    }

    // Seul l'utilisateur modifie son consentement.
    @PUT
    @Path("/me")
    public Response updateConsent(ConsentRequest request) {

        if (request == null || request.enabled == null) {
            return Response.status(400)
                    .entity(Map.of(
                            "error", "enabled est obligatoire"
                    ))
                    .build();
        }

        String sql = """
            INSERT INTO gps_consents(user_id, enabled)
            VALUES(?, ?)
            ON DUPLICATE KEY UPDATE
                enabled = VALUES(enabled)
            """;

        try (
                Connection c = DatabaseConnection.getConnection();
                PreparedStatement p = c.prepareStatement(sql)
        ) {
            p.setLong(1, currentId());
            p.setBoolean(2, request.enabled);

            p.executeUpdate();

            return Response.ok(Map.of(
                    "message", request.enabled
                            ? "Partage GPS active"
                            : "Partage GPS desactive",
                    "enabled", request.enabled
            )).build();

        } catch (SQLException e) {
            e.printStackTrace();
            return Response.serverError().build();
        }
    }
}
