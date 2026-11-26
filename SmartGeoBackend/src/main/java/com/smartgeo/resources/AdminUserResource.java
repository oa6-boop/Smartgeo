
package com.smartgeo.resources;

import com.smartgeo.dao.DatabaseConnection;
import com.smartgeo.dao.UserDAO;
import com.smartgeo.dto.ApiResponse;
import com.smartgeo.entities.User;
import com.smartgeo.security.PasswordUtil;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Map;

@Path("/admin/users")
@Secured(roles = {"ADMIN"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AdminUserResource {

    private final UserDAO userDAO = new UserDAO();

    @Context
    private SecurityContext securityContext;

    public static class StatusRequest {
        public String status;
    }

    public static class RoleRequest {
        public String role;
    }

    public static class CreateUserRequest {
        public String fullName;
        public String username;
        public String email;
        public String phone;
        public String password;
        public String role;
    }

    private long adminId() {
        return Long.parseLong(
                securityContext.getUserPrincipal().getName()
        );
    }

    private boolean isActiveAdmin() throws Exception {
        User admin = userDAO.findById(adminId());

        return admin != null
                && "ADMIN".equals(admin.getRole())
                && "ACTIVE".equals(admin.getStatus());
    }

    private Response error(int code, String message) {
        return Response.status(code)
                .entity(ApiResponse.error(message))
                .build();
    }

    // GET /api/admin/users
    @GET
    public Response list() {
        try {
            if (!isActiveAdmin()) {
                return error(403, "Acces administrateur interdit.");
            }

            return Response.ok(
                    ApiResponse.ok(
                            "Liste des utilisateurs.",
                            userDAO.findAll()
                    )
            ).build();

        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Erreur de lecture.");
        }
    }

    // GET /api/admin/users/{id}
    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") long id) {
        try {
            if (!isActiveAdmin()) {
                return error(403, "Acces administrateur interdit.");
            }

            User user = userDAO.findById(id);

            if (user == null) {
                return error(404, "Utilisateur introuvable.");
            }

            return Response.ok(
                    ApiResponse.ok("Utilisateur trouve.", user)
            ).build();

        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Erreur de lecture.");
        }
    }

    // POST /api/admin/users
    @POST
    public Response create(CreateUserRequest request) {
        try {
            if (!isActiveAdmin()) {
                return error(403, "Acces administrateur interdit.");
            }

            if (request == null
                    || request.fullName == null
                    || request.fullName.isBlank()
                    || request.username == null
                    || request.username.isBlank()
                    || request.password == null
                    || request.password.length() < 8) {

                return error(
                        400,
                        "Nom, identifiant et mot de passe de 8 caracteres minimum obligatoires."
                );
            }

            String username = request.username.trim();

            if (userDAO.findByUsername(username) != null) {
                return error(409, "Cet identifiant existe deja.");
            }

            String role = request.role == null
                    ? "USER"
                    : request.role.trim().toUpperCase();

            if (!role.equals("ADMIN") && !role.equals("USER")) {
                return error(400, "Role invalide.");
            }

            String email = request.email == null
                    ? null
                    : request.email.trim();

            if (email != null
                    && !email.isEmpty()
                    && (!email.contains("@") || email.contains(" "))) {
                return error(400, "Email invalide.");
            }

            if (email != null && email.isEmpty()) {
                email = null;
            }

            User user = new User();
            user.setFullName(request.fullName.trim());
            user.setUsername(username);
            user.setEmail(email);
            user.setPhone(request.phone == null
                    ? null : request.phone.trim());
            user.setRole(role);
            user.setStatus("ACTIVE");

            String hash = PasswordUtil.hash(request.password);
            long id = userDAO.create(user, hash);

            User created = userDAO.findById(id);

            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(
                            "Utilisateur cree.",
                            created
                    ))
                    .build();

        } catch (SQLIntegrityConstraintViolationException e) {
            return error(409, "Identifiant ou email deja utilise.");
        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Creation impossible.");
        }
    }

    // PUT /api/admin/users/{id}/profile
    @PUT
    @Path("/{id}/profile")
    public Response updateProfile(
            @PathParam("id") long id,
            User request
    ) {
        try {
            if (!isActiveAdmin()) {
                return error(403, "Acces administrateur interdit.");
            }

            if (request == null) {
                return error(400, "Donnees manquantes.");
            }

            User user = userDAO.findById(id);

            if (user == null) {
                return error(404, "Utilisateur introuvable.");
            }

            if (request.getFullName() != null) {
                String name = request.getFullName().trim();

                if (name.isEmpty()) {
                    return error(400, "Nom invalide.");
                }

                user.setFullName(name);
            }

            if (request.getEmail() != null) {
                String email = request.getEmail().trim();

                if (email.isEmpty() || !email.contains("@")) {
                    return error(400, "Email invalide.");
                }

                user.setEmail(email);
            }

            if (request.getPhone() != null) {
                user.setPhone(request.getPhone().trim());
            }

            userDAO.update(user);

            return Response.ok(
                    ApiResponse.ok(
                            "Profil modifie.",
                            userDAO.findById(id)
                    )
            ).build();

        } catch (SQLIntegrityConstraintViolationException e) {
            return error(409, "Email deja utilise.");
        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Modification impossible.");
        }
    }

    // PUT /api/admin/users/{id}/status
    @PUT
    @Path("/{id}/status")
    public Response changeStatus(
            @PathParam("id") long id,
            StatusRequest request
    ) {
        if (request == null || request.status == null) {
            return error(400, "Statut obligatoire.");
        }

        String status = request.status.trim().toUpperCase();

        if (!status.equals("ACTIVE")
                && !status.equals("INACTIVE")
                && !status.equals("BLOCKED")) {
            return error(400, "Statut invalide.");
        }

        return changeAccess(id, null, status);
    }

    // PUT /api/admin/users/{id}/role
    @PUT
    @Path("/{id}/role")
    public Response changeRole(
            @PathParam("id") long id,
            RoleRequest request
    ) {
        if (request == null || request.role == null) {
            return error(400, "Role obligatoire.");
        }

        String role = request.role.trim().toUpperCase();

        if (!role.equals("ADMIN") && !role.equals("USER")) {
            return error(400, "Role invalide.");
        }

        return changeAccess(id, role, null);
    }

    // Transaction protegeant le dernier ADMIN actif.
    private Response changeAccess(
            long targetId,
            String newRole,
            String newStatus
    ) {
        try (Connection c = DatabaseConnection.getConnection()) {

            c.setAutoCommit(false);

            try {
                try (PreparedStatement lock = c.prepareStatement(
                        "SELECT id FROM users ORDER BY id FOR UPDATE"
                ); ResultSet rs = lock.executeQuery()) {
                    while (rs.next()) {
                        rs.getLong(1);
                    }
                }

                long actorId = adminId();

                String actorRole = null;
                String actorStatus = null;
                String targetRole = null;
                String targetStatus = null;
                int activeAdmins = 0;

                try (PreparedStatement p = c.prepareStatement(
                        "SELECT id, role, status FROM users"
                ); ResultSet rs = p.executeQuery()) {

                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String role = rs.getString("role");
                        String status = rs.getString("status");

                        if ("ADMIN".equals(role)
                                && "ACTIVE".equals(status)) {
                            activeAdmins++;
                        }

                        if (id == actorId) {
                            actorRole = role;
                            actorStatus = status;
                        }

                        if (id == targetId) {
                            targetRole = role;
                            targetStatus = status;
                        }
                    }
                }

                if (!"ADMIN".equals(actorRole)
                        || !"ACTIVE".equals(actorStatus)) {
                    c.rollback();
                    return error(403, "Administrateur non autorise.");
                }

                if (targetRole == null) {
                    c.rollback();
                    return error(404, "Utilisateur introuvable.");
                }

                String finalRole = newRole == null
                        ? targetRole : newRole;

                String finalStatus = newStatus == null
                        ? targetStatus : newStatus;

                boolean wasActiveAdmin =
                        "ADMIN".equals(targetRole)
                                && "ACTIVE".equals(targetStatus);

                boolean willBeActiveAdmin =
                        "ADMIN".equals(finalRole)
                                && "ACTIVE".equals(finalStatus);

                if (wasActiveAdmin
                        && !willBeActiveAdmin
                        && activeAdmins <= 1) {
                    c.rollback();
                    return error(
                            409,
                            "Impossible de retirer le dernier ADMIN actif."
                    );
                }

                if (actorId == targetId && !willBeActiveAdmin) {
                    c.rollback();
                    return error(
                            409,
                            "Vous ne pouvez pas retirer votre propre acces."
                    );
                }

                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE users SET role=?, status=? WHERE id=?"
                )) {
                    p.setString(1, finalRole);
                    p.setString(2, finalStatus);
                    p.setLong(3, targetId);
                    p.executeUpdate();
                }

                c.commit();

                return Response.ok(
                        ApiResponse.ok(
                                "Acces utilisateur modifie.",
                                Map.of(
                                        "id", targetId,
                                        "role", finalRole,
                                        "status", finalStatus
                                )
                        )
                ).build();

            } catch (Exception e) {
                c.rollback();
                throw e;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return error(
                    500,
                    "Modification des droits impossible."
            );
        }
    }
}
