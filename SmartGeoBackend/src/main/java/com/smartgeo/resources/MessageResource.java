package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.*;
import java.util.*;

@Path("/messages")
@Secured
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MessageResource extends BaseResource {

    public record NewMessage(long receiverId, String content) {}

    // Seuls les contacts actifs de l'autre role sont proposes.
    @GET
    @Path("/contacts")
    public Response contacts() {
        String role = admin() ? "USER" : "ADMIN";
        String sql = "SELECT id,full_name,username,role FROM users " +
                "WHERE role=? AND status='ACTIVE' AND id<>? ORDER BY full_name,username";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, role);
            p.setLong(2, currentId());
            try (ResultSet r = p.executeQuery()) {
                List<Map<String,Object>> result = new ArrayList<>();
                while (r.next()) {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("id", r.getLong("id"));
                    item.put("fullName", r.getString("full_name"));
                    item.put("username", r.getString("username"));
                    item.put("role", r.getString("role"));
                    result.add(item);
                }
                return Response.ok(result).build();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Impossible de charger les contacts.");
        }
    }

    @GET
    @Path("/inbox")
    public Response inbox() {
        String sql = "SELECT m.id,m.sender_id,m.receiver_id,m.content,m.sent_at,m.read_at," +
                "u.full_name AS sender_name FROM messages m " +
                "JOIN users u ON u.id=m.sender_id WHERE m.receiver_id=? " +
                "ORDER BY m.sent_at DESC,m.id DESC LIMIT 100";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, currentId());
            try (ResultSet r = p.executeQuery()) {
                List<Map<String,Object>> result = new ArrayList<>();
                while (r.next()) result.add(messageRow(r, true));
                return Response.ok(result).build();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Lecture des messages impossible.");
        }
    }

    @POST
    public Response send(NewMessage x) {
        if (x == null || x.receiverId() <= 0 || x.receiverId() == currentId()
                || x.content() == null || x.content().isBlank() || x.content().length() > 4000) {
            return error(400, "Destinataire et texte (1 a 4000 caracteres) obligatoires.");
        }
        try (Connection c = Database.getConnection()) {
            if (!validContact(c, x.receiverId())) return error(403, "Destinataire non autorise ou inactif.");
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO messages(sender_id,receiver_id,content,sent_at) VALUES(?,?,?,NOW())",
                    Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, currentId());
                p.setLong(2, x.receiverId());
                p.setString(3, x.content().trim());
                p.executeUpdate();
                try (ResultSet r = p.getGeneratedKeys()) {
                    if (r.next()) return Response.status(201).entity(Map.of("id", r.getLong(1))).build();
                }
            }
            return error(500, "Identifiant de message introuvable.");
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Envoi impossible.");
        }
    }

    @GET
    @Path("/conversation/{otherId}")
    public Response conversation(@PathParam("otherId") long otherId) {
        try (Connection c = Database.getConnection()) {
            if (!validContact(c, otherId)) return error(403, "Contact non autorise.");
            String sql = "SELECT id,sender_id,receiver_id,content,sent_at,read_at FROM messages " +
                    "WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?) " +
                    "ORDER BY sent_at ASC,id ASC LIMIT 1000";
            try (PreparedStatement p = c.prepareStatement(sql)) {
                p.setLong(1, currentId()); p.setLong(2, otherId);
                p.setLong(3, otherId); p.setLong(4, currentId());
                try (ResultSet r = p.executeQuery()) {
                    List<Map<String,Object>> result = new ArrayList<>();
                    while (r.next()) result.add(messageRow(r, false));
                    return Response.ok(result).build();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Conversation indisponible.");
        }
    }

    @PUT
    @Path("/{id}/read")
    public Response markRead(@PathParam("id") long id) {
        if (id <= 0) return error(400, "Message invalide.");
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(
                "UPDATE messages SET read_at=COALESCE(read_at,NOW()) WHERE id=? AND receiver_id=?")) {
            p.setLong(1, id);
            p.setLong(2, currentId());
            return p.executeUpdate() == 0 ? error(404, "Message introuvable.")
                    : Response.ok(Map.of("message", "Message lu.")).build();
        } catch (SQLException e) {
            e.printStackTrace();
            return error(500, "Modification impossible.");
        }
    }

    private boolean validContact(Connection c, long id) throws SQLException {
        if (id <= 0 || id == currentId()) return false;
        try (PreparedStatement p = c.prepareStatement(
                "SELECT 1 FROM users WHERE id=? AND role=? AND status='ACTIVE'")) {
            p.setLong(1, id);
            p.setString(2, admin() ? "USER" : "ADMIN");
            try (ResultSet r = p.executeQuery()) { return r.next(); }
        }
    }

    private Map<String,Object> messageRow(ResultSet r, boolean inbox) throws SQLException {
        Map<String,Object> m = new LinkedHashMap<>();
        for (String key : List.of("id", "sender_id", "receiver_id", "content", "sent_at", "read_at")) {
            m.put(key, r.getObject(key));
        }
        if (inbox) m.put("sender_name", r.getString("sender_name"));
        return m;
    }

    private Response error(int status, String message) {
        return Response.status(status).entity(Map.of("success", false, "message", message)).build();
    }
}
