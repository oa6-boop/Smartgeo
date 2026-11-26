package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.*;
import java.util.*;

@Path("/location-requests")
@Secured
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class LocationRequestResource extends BaseResource {

    public record RequestInput(long userId) {}
    public record ResponseInput(String status, Long positionId) {}

    @POST
    public Response create(RequestInput x) {
        if (!admin()) return forbidden();
        if (x == null || x.userId() <= 0) return error(400, "Utilisateur obligatoire.");
        try (Connection c = Database.getConnection()) {
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM users WHERE id=? AND role='USER' AND status='ACTIVE'")) {
                check.setLong(1, x.userId());
                try (ResultSet r = check.executeQuery()) {
                    if (!r.next()) return error(404, "Agent actif introuvable.");
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO location_requests(admin_id,user_id,requested_at,status) " +
                            "VALUES(?,?,NOW(),'PENDING')", Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, currentId());
                p.setLong(2, x.userId());
                p.executeUpdate();
                try (ResultSet r = p.getGeneratedKeys()) {
                    if (r.next()) return Response.status(201).entity(Map.of("id", r.getLong(1), "status", "PENDING")).build();
                }
            }
            return error(500, "Identifiant absent.");
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Demande impossible.");
        }
    }

    @GET
    public Response list() {
        String sql = "SELECT q.id,q.admin_id,q.user_id,q.requested_at,q.responded_at,q.status,q.position_id," +
                "a.full_name AS admin_name,u.full_name AS user_name " +
                "FROM location_requests q JOIN users a ON a.id=q.admin_id " +
                "JOIN users u ON u.id=q.user_id " +
                (admin() ? "" : "WHERE q.user_id=? ") +
                "ORDER BY q.requested_at DESC,q.id DESC";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            if (!admin()) p.setLong(1, currentId());
            try (ResultSet r = p.executeQuery()) {
                List<Map<String,Object>> result = new ArrayList<>();
                while (r.next()) {
                    Map<String,Object> item = new LinkedHashMap<>();
                    for (String key : List.of("id","admin_id","user_id","requested_at","responded_at","status","position_id","admin_name","user_name")) {
                        item.put(key, r.getObject(key));
                    }
                    result.add(item);
                }
                return Response.ok(result).build();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Lecture des demandes impossible.");
        }
    }

    // Transmission possible uniquement apres un POST /positions valide.
    @PUT
    @Path("/{id}/respond")
    public Response respond(@PathParam("id") long id, ResponseInput x) {
        if (admin()) return forbidden();
        if (id <= 0 || x == null || x.status() == null || !List.of("REFUSED", "TRANSMITTED").contains(x.status())) {
            return error(400, "Statut autorise : REFUSED ou TRANSMITTED.");
        }
        if ("TRANSMITTED".equals(x.status()) && (x.positionId() == null || x.positionId() <= 0)) {
            return error(400, "Une position GPS valide est obligatoire pour transmettre.");
        }
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                if ("TRANSMITTED".equals(x.status())) {
                    try (PreparedStatement check = c.prepareStatement(
                            "SELECT 1 FROM positions WHERE id=? AND user_id=? " +
                                    "AND received_at >= UTC_TIMESTAMP() - INTERVAL 5 MINUTE")) {
                        check.setLong(1, x.positionId());
                        check.setLong(2, currentId());
                        try (ResultSet r = check.executeQuery()) {
                            if (!r.next()) { c.rollback(); return error(403, "Position non autorisee."); }
                        }
                    }
                }
                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE location_requests SET status=?,responded_at=NOW(),position_id=? " +
                                "WHERE id=? AND user_id=? AND status='PENDING'")) {
                    p.setString(1, x.status());
                    if ("TRANSMITTED".equals(x.status())) p.setLong(2, x.positionId());
                    else p.setNull(2, Types.BIGINT);
                    p.setLong(3, id);
                    p.setLong(4, currentId());
                    int n = p.executeUpdate();
                    if (n == 0) { c.rollback(); return error(409, "Demande introuvable ou deja traitee."); }
                }
                c.commit();
                return Response.ok(Map.of("message", "Reponse enregistree.", "status", x.status())).build();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Reponse impossible.");
        }
    }

    private Response error(int code, String message) {
        return Response.status(code).entity(Map.of("success", false, "message", message)).build();
    }
}
