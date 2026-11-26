
package com.smartgeo.resources;

import com.smartgeo.security.Secured;
import com.smartgeo.services.GpsScheduleService;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;
import java.time.DateTimeException;
import java.util.Map;

@Path("/gps/status")
@Secured
@Produces(MediaType.APPLICATION_JSON)
public class GpsStatusResource extends BaseResource {

    private final GpsScheduleService gpsService =
            new GpsScheduleService();

    @GET
    public Response getStatus() {

        long userId = currentId();

        if (userId <= 0) {
            return Response.status(401)
                    .entity(Map.of(
                            "success", false,
                            "message", "Utilisateur non authentifie"
                    ))
                    .build();
        }

        try {
            GpsScheduleService.Decision decision =
                    gpsService.check(userId);

            boolean allowed =
                    decision == GpsScheduleService.Decision.ALLOWED;

            String message = switch (decision) {

                case ALLOWED ->
                        "Suivi GPS autorise";

                case ACCOUNT_INACTIVE ->
                        "Compte utilisateur inactif";

                case CONSENT_DISABLED ->
                        "Partage GPS desactive";

                case OUTSIDE_SCHEDULE ->
                        "En dehors des horaires autorises";
            };

            return Response.ok(Map.of(
                    "userId", userId,
                    "allowed", allowed,
                    "status", decision.name(),
                    "message", message
            )).build();

        } catch (SQLException | DateTimeException e) {

            e.printStackTrace();

            return Response.serverError()
                    .entity(Map.of(
                            "success", false,
                            "message",
                            "Verification GPS indisponible"
                    ))
                    .build();
        }
    }
}
