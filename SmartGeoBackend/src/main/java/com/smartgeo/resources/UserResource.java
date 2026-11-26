
package com.smartgeo.resources;

import com.smartgeo.dao.UserDAO;
import com.smartgeo.dto.ApiResponse;
import com.smartgeo.entities.User;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Map;

@Path("/users")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    private final UserDAO userDAO = new UserDAO();

    @Context
    private SecurityContext securityContext;

    private long currentUserId() {

        if (securityContext == null
                || securityContext.getUserPrincipal() == null) {
            return -1;
        }

        return Long.parseLong(
                securityContext.getUserPrincipal().getName()
        );
    }

    @GET
    @Path("/me")
    @Secured
    public Response getMyProfile() {

        try {
            User user = userDAO.findById(currentUserId());

            if (user == null) {
                return Response.status(404)
                        .entity(ApiResponse.error("Utilisateur introuvable."))
                        .build();
            }

            return Response.ok(
                    ApiResponse.ok("Profil récupéré.", user)
            ).build();

        } catch (Exception e) {
            e.printStackTrace();

            return Response.serverError()
                    .entity(ApiResponse.error("Erreur lors de la récupération du profil."))
                    .build();
        }
    }

    @PUT
    @Path("/me")
    @Secured
    public Response updateMyProfile(User request) {

        try {

            if (request == null) {
                return Response.status(400)
                        .entity(ApiResponse.error("Données manquantes."))
                        .build();
            }

            long id = currentUserId();

            User current = userDAO.findById(id);

            if (current == null) {
                return Response.status(404)
                        .entity(ApiResponse.error("Utilisateur introuvable."))
                        .build();
            }

            // Seules ces informations peuvent être modifiées.
            if (request.getFullName() != null) {
                String name = request.getFullName().trim();

                if (name.isBlank() || name.length() > 150) {
                    return Response.status(400)
                            .entity(ApiResponse.error("Nom invalide."))
                            .build();
                }

                current.setFullName(name);
            }

            if (request.getEmail() != null) {
                String email = request.getEmail().trim();
                if (email.length() > 160 || (!email.isEmpty() &&
                        (!email.contains("@") || email.contains(" ")))) {
                    return Response.status(400)
                            .entity(ApiResponse.error("Email invalide."))
                            .build();
                }
                current.setEmail(email.isEmpty() ? null : email);
            }

            if (request.getPhone() != null) {
                String phone = request.getPhone().trim();
                if (phone.length() > 30) {
                    return Response.status(400)
                            .entity(ApiResponse.error("Telephone trop long."))
                            .build();
                }
                current.setPhone(phone.isEmpty() ? null : phone);
            }

            // Le rôle, le statut et le username restent inchangés.
            // Une sauvegarde avec des valeurs identiques est aussi un succes.
            userDAO.update(current);

            return Response.ok(
                    ApiResponse.ok("Profil modifié.", current)
            ).build();

        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            return Response.status(409)
                    .entity(ApiResponse.error("Cet email est deja utilise."))
                    .build();
        } catch (Exception e) {
            e.printStackTrace();

            return Response.serverError()
                    .entity(ApiResponse.error("Erreur lors de la modification."))
                    .build();
        }
    }

    @GET
    @Secured(roles = {"ADMIN"})
    public Response getAllUsers() {

        try {

            return Response.ok(
                    ApiResponse.ok(
                            "Liste des utilisateurs.",
                            userDAO.findAll()
                    )
            ).build();

        } catch (Exception e) {
            e.printStackTrace();

            return Response.serverError()
                    .entity(ApiResponse.error("Impossible de récupérer les utilisateurs."))
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    @Secured
    public Response getUserById(@PathParam("id") long id) {

        try {

            boolean admin = securityContext != null
                    && securityContext.isUserInRole("ADMIN");

            if (!admin && id != currentUserId()) {
                return Response.status(403)
                        .entity(ApiResponse.error("Accès interdit."))
                        .build();
            }

            User user = userDAO.findById(id);

            if (user == null) {
                return Response.status(404)
                        .entity(ApiResponse.error("Utilisateur introuvable."))
                        .build();
            }

            return Response.ok(
                    ApiResponse.ok("Utilisateur récupéré.", user)
            ).build();

        } catch (Exception e) {
            e.printStackTrace();

            return Response.serverError()
                    .entity(ApiResponse.error("Erreur lors de la récupération."))
                    .build();
        }
    }
}
