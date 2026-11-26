package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.*;
import java.util.*;

@Path("/reports")
@Secured
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ReportResource extends BaseResource {

    public record ReportInput(long missionId, String title, String description) {}

    @GET
    public Response list() {
        String sql = "SELECT r.id,r.mission_id,r.user_id,r.title,r.description,r.status," +
                "r.created_at,r.updated_at,m.title AS mission_title,u.full_name AS user_name " +
                "FROM reports r JOIN missions m ON m.id=r.mission_id " +
                "JOIN users u ON u.id=r.user_id " +
                (admin() ? "" : "WHERE r.user_id=? ") +
                "ORDER BY r.created_at DESC,r.id DESC";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            if (!admin()) p.setLong(1, currentId());
            try (ResultSet r = p.executeQuery()) {
                List<Map<String,Object>> result = new ArrayList<>();
                while (r.next()) {
                    Map<String,Object> item = new LinkedHashMap<>();
                    for (String key : List.of("id","mission_id","user_id","title","description", "status", "created_at","updated_at","mission_title","user_name")) {
                        item.put(key, r.getObject(key));
                    }
                    result.add(item);
                }
                return Response.ok(result).build();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Lecture des signalements impossible.");
        }
    }

    @POST
    public Response create(ReportInput x) {
        if (admin()) return error(403, "Seuls les agents soumettent un signalement.");
        if (x == null || x.missionId() <= 0 || x.title() == null || x.title().isBlank()
                || x.title().length() > 180 || x.description() == null || x.description().isBlank()
                || x.description().length() > 20000) {
            return error(400, "Mission, titre (180 caracteres max) et description (20000 max) obligatoires.");
        }
        try (Connection c = Database.getConnection()) {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM missions WHERE id=? AND assigned_user_id=? AND status<>'CANCELLED'")) {
                check.setLong(1, x.missionId());
                check.setLong(2, currentId());
                try (ResultSet r = check.executeQuery()) {
                    if (!r.next()) return error(403, "Signalement reserve a une mission qui vous est affectee.");
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO reports(mission_id,user_id,title,description,status,created_at,updated_at) " +
                            "VALUES(?,?,?,?,'SUBMITTED',NOW(),NOW())", Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, x.missionId());
                p.setLong(2, currentId());
                p.setString(3, x.title().trim());
                p.setString(4, x.description().trim());
                p.executeUpdate();
                try (ResultSet r = p.getGeneratedKeys()) {
                    if (r.next()) return Response.status(201).entity(Map.of("id", r.getLong(1))).build();
                }
            }
            return error(500, "Identifiant du signalement absent.");
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Creation du signalement impossible.");
        }
    }

    @PUT
    @Path("/{id}/status")
    public Response updateStatus(@PathParam("id") long id, Map<String,String> x) {
        if (!admin()) return forbidden();
        String status = x == null ? null : x.get("status");
        if (id <= 0 || status == null || !List.of("SUBMITTED", "REVIEWED", "REJECTED").contains(status)) {
            return error(400, "Statut invalide.");
        }
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(
                "UPDATE reports SET status=?,updated_at=NOW() WHERE id=?")) {
            p.setString(1, status);
            p.setLong(2, id);
            int updated = p.executeUpdate();
            if (updated > 0) return Response.ok(Map.of("message", "Statut du signalement modifie.")).build();
            try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM reports WHERE id=?")) {
                check.setLong(1, id);
                try (ResultSet r = check.executeQuery()) {
                    return r.next() ? Response.ok(Map.of("message", "Statut deja applique.")).build()
                            : error(404, "Signalement introuvable.");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Mise a jour impossible.");
        }
    }

    private Response error(int code, String message) {
        return Response.status(code).entity(Map.of("success", false, "message", message)).build();
    }
}
