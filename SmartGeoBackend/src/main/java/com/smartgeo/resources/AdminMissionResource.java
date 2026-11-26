
package com.smartgeo.resources;

import com.smartgeo.dao.DatabaseConnection;
import com.smartgeo.dao.UserDAO;
import com.smartgeo.entities.User;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Map;

@Path("/admin/missions")
@Secured(roles = {"ADMIN"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AdminMissionResource {

    private final UserDAO userDAO = new UserDAO();

    @Context
    private SecurityContext securityContext;

    public static class AssignmentRequest {
        public Long assignedUserId;
    }

    private boolean authorized() throws Exception {
        if (securityContext == null ||
                securityContext.getUserPrincipal() == null) {
            return false;
        }

        long id = Long.parseLong(
                securityContext.getUserPrincipal().getName()
        );

        User user = userDAO.findById(id);

        return user != null
                && "ADMIN".equals(user.getRole())
                && "ACTIVE".equals(user.getStatus());
    }

    private Response error(int status, String message) {
        return Response.status(status)
                .entity(Map.of(
                        "success", false,
                        "message", message
                ))
                .build();
    }

    // PUT /api/admin/missions/{id}/assign
    @PUT
    @Path("/{id}/assign")
    public Response assign(
            @PathParam("id") long missionId,
            AssignmentRequest request
    ) {
        try {
            if (!authorized()) {
                return error(403, "Acces administrateur interdit.");
            }

            if (missionId <= 0 || request == null ||
                    request.assignedUserId == null ||
                    request.assignedUserId <= 0) {
                return error(400, "Identifiants invalides.");
            }

            try (Connection c =
                         DatabaseConnection.getConnection()) {

                c.setAutoCommit(false);

                try {
                    // Verifier et verrouiller la mission.
                    try (PreparedStatement p = c.prepareStatement(
                            "SELECT id FROM missions WHERE id=? FOR UPDATE"
                    )) {
                        p.setLong(1, missionId);

                        try (ResultSet r = p.executeQuery()) {
                            if (!r.next()) {
                                c.rollback();
                                return error(404, "Mission introuvable.");
                            }
                        }
                    }

                    // Verifier que le destinataire est actif.
                    try (PreparedStatement p = c.prepareStatement(
                            "SELECT role, status FROM users WHERE id=?"
                    )) {
                        p.setLong(1, request.assignedUserId);

                        try (ResultSet r = p.executeQuery()) {
                            if (!r.next()) {
                                c.rollback();
                                return error(404, "Utilisateur introuvable.");
                            }

                            if (!"USER".equals(r.getString("role")) ||
                                    !"ACTIVE".equals(r.getString("status"))) {
                                c.rollback();
                                return error(
                                        409,
                                        "La mission doit etre attribuee a un USER actif."
                                );
                            }
                        }
                    }

                    try (PreparedStatement p = c.prepareStatement(
                            "UPDATE missions SET assigned_user_id=? WHERE id=?"
                    )) {
                        p.setLong(1, request.assignedUserId);
                        p.setLong(2, missionId);
                        p.executeUpdate();
                    }

                    c.commit();

                    return Response.ok(Map.of(
                            "success", true,
                            "message", "Mission attribuee.",
                            "missionId", missionId,
                            "assignedUserId", request.assignedUserId
                    )).build();

                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Attribution impossible.");
        }
    }

    // PUT /api/admin/missions/{id}/details
    @PUT
    @Path("/{id}/details")
    public Response updateDetails(
            @PathParam("id") long id,
            Map<String, String> request
    ) {
        try {
            if (!authorized()) {
                return error(403, "Acces administrateur interdit.");
            }

            if (id <= 0 || request == null) {
                return error(400, "Donnees invalides.");
            }

            String title = request.get("title");
            String description = request.get("description");

            if (title == null || title.isBlank()) {
                return error(400, "Titre obligatoire.");
            }

            if (title.length() > 180) {
                return error(400, "Titre trop long.");
            }

            try (Connection c =
                         DatabaseConnection.getConnection();
                 PreparedStatement p = c.prepareStatement(
                         "UPDATE missions SET title=?, description=? WHERE id=?"
                 )) {

                p.setString(1, title.trim());

                if (description == null) {
                    p.setNull(2, Types.VARCHAR);
                } else {
                    p.setString(2, description.trim());
                }

                p.setLong(3, id);

                int updated = p.executeUpdate();

                if (updated == 0) {
                    return error(404, "Mission introuvable.");
                }

                return Response.ok(Map.of(
                        "success", true,
                        "message", "Mission modifiee.",
                        "missionId", id
                )).build();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Modification impossible.");
        }
    }
}
