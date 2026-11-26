
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.sql.*;
import java.util.*;

@Path("/missions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class MissionResource extends BaseResource {

    public record MissionInput(
            String title,
            String description,
            Long assignedUserId,
            String startDate,
            String endDate,
            String status
    ) {}

    @GET
    public Response list() {

        String sql = admin()
                ? "SELECT * FROM missions ORDER BY created_at DESC"
                : "SELECT * FROM missions WHERE assigned_user_id=? ORDER BY created_at DESC";

        try (
                Connection c = Database.getConnection();
                PreparedStatement p = c.prepareStatement(sql)
        ) {
            if (!admin()) p.setLong(1, currentId());

            try (ResultSet r = p.executeQuery()) {

                List<Map<String, Object>> missions = new ArrayList<>();

                while (r.next()) missions.add(row(r));

                return Response.ok(missions).build();
            }

        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    @POST
    public Response create(MissionInput x) {

        if (!admin()) return forbidden();

        if (x == null || x.title() == null || x.title().isBlank()
                || x.title().length() > 180) {
            return Response.status(400).entity(Map.of("message", "Titre obligatoire (180 caracteres max). ")).build();
        }
        if (x.status() != null && !List.of("PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED").contains(x.status())) {
            return Response.status(400).entity(Map.of("message", "Statut invalide.")).build();
        }
        try {
            if (x.startDate() != null) java.time.LocalDate.parse(x.startDate());
            if (x.endDate() != null) java.time.LocalDate.parse(x.endDate());
            if (x.startDate() != null && x.endDate() != null
                    && java.time.LocalDate.parse(x.endDate()).isBefore(java.time.LocalDate.parse(x.startDate()))) {
                return Response.status(400).entity(Map.of("message", "Dates incoherentes.")).build();
            }
        } catch (java.time.format.DateTimeParseException e) {
            return Response.status(400).entity(Map.of("message", "Dates attendues au format yyyy-MM-dd.")).build();
        }
        try {
            if (x.assignedUserId() != null) {
                try (Connection c = Database.getConnection();
                     PreparedStatement p = c.prepareStatement("SELECT role,status FROM users WHERE id=?")) {
                    p.setLong(1, x.assignedUserId());
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next() || !"USER".equals(r.getString("role")) || !"ACTIVE".equals(r.getString("status"))) {
                            return Response.status(409).entity(Map.of("message", "Affectation reservee a un USER actif.")).build();
                        }
                    }
                }
            }
            long id = Database.insert(
                    "INSERT INTO missions(title,description,assigned_user_id,created_by,start_date,end_date,status) " +
                            "VALUES(?,?,?,?,?,?,?)",
                    p -> {
                        p.setString(1, x.title());
                        p.setString(2, x.description());

                        if (x.assignedUserId() == null) {
                            p.setNull(3, Types.BIGINT);
                        } else {
                            p.setLong(3, x.assignedUserId());
                        }

                        p.setLong(4, currentId());
                        p.setString(5, x.startDate());
                        p.setString(6, x.endDate());
                        p.setString(
                                7,
                                x.status() == null ? "PENDING" : x.status()
                        );
                    }
            );

            return Response.status(201)
                    .entity(Map.of("id", id))
                    .build();

        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    @PUT
    @Path("/{id}/status")
    public Response status(
            @PathParam("id") long id,
            Map<String, String> x
    ) {

        if (x == null) return Response.status(400).build();
        String status = x.get("status");

        if (status == null || !List.of(
                "PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED"
        ).contains(status)) {
            return Response.status(400).entity(Map.of("message", "Statut invalide.")).build();
        }
        if (!admin() && "CANCELLED".equals(status)) {
            return forbidden();
        }

        try {
            int n = Database.update(
                    "UPDATE missions SET status=? WHERE id=? " +
                            "AND (assigned_user_id=? OR ?='ADMIN')",
                    p -> {
                        p.setString(1, status);
                        p.setLong(2, id);
                        p.setLong(3, currentId());
                        p.setString(4, currentRole());
                    }
            );

            if (n > 0) return Response.ok(Map.of("message", "Mission modifiee.")).build();
            // MySQL peut retourner 0 si le statut etait deja identique.
            String checkSql = admin() ? "SELECT 1 FROM missions WHERE id=?" :
                    "SELECT 1 FROM missions WHERE id=? AND assigned_user_id=?";
            try (Connection c = Database.getConnection(); PreparedStatement check = c.prepareStatement(checkSql)) {
                check.setLong(1, id);
                if (!admin()) check.setLong(2, currentId());
                try (ResultSet r = check.executeQuery()) {
                    return r.next() ? Response.ok(Map.of("message", "Statut deja applique.")).build()
                            : Response.status(404).build();
                }
            }

        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    private Map<String, Object> row(ResultSet r)
            throws SQLException {

        Map<String, Object> data = new LinkedHashMap<>();

        for (String key : List.of(
                "id", "title", "description", "assigned_user_id",
                "created_by", "start_date", "end_date",
                "status", "created_at"
        )) {
            data.put(key, r.getObject(key));
        }

        return data;
    }
}
