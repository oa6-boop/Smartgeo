
package com.smartgeo.resources;

import com.smartgeo.database.Database;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

@Path("/health/database")
@Produces(MediaType.APPLICATION_JSON)
public class DatabaseHealthResource {

    @GET
    public Response checkDatabase() {

        try (
                Connection connection = Database.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT 1");
                ResultSet result = statement.executeQuery()
        ) {

            if (result.next()) {

                return Response.ok(
                        Map.of(
                                "application", "SmartGeo API",
                                "database", "smartgeo_db",
                                "status", "CONNECTED"
                        )
                ).build();
            }

            return Response.status(503)
                    .entity(Map.of(
                            "status", "ERROR",
                            "message", "La base de données ne répond pas."
                    ))
                    .build();

        } catch (Exception e) {

            return Response.status(503)
                    .entity(Map.of(
                            "application", "SmartGeo API",
                            "status", "DATABASE_ERROR",
                            "message", "Impossible de se connecter à MySQL."
                    ))
                    .build();
        }
    }
}
