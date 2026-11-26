
package com.smartgeo.resources;

import com.smartgeo.dto.ApiResponse;
import com.smartgeo.dto.LoginRequest;
import com.smartgeo.entities.User;
import com.smartgeo.security.JwtUtil;
import com.smartgeo.services.AuthService;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    private final AuthService service = new AuthService();

    public static class RegisterRequest {
        public User user;
        public String password;
    }

    @POST
    @Path("/register")
    public Response register(RegisterRequest request) {
        try {
            if (request == null || request.user == null
                    || request.password == null
                    || request.password.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Données manquantes."))
                        .build();
            }

            User createdUser = service.register(
                    request.user,
                    request.password
            );

            String token = JwtUtil.create(
                    createdUser.getId(),
                    createdUser.getRole()
            );

            Map<String, Object> data = Map.of(
                    "id", createdUser.getId(),
                    "token", token,
                    "tokenType", "Bearer",
                    "expiresIn", 86400,
                    "user", createdUser
            );

            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok("Compte créé avec succès.", data))
                    .build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error(e.getMessage()))
                    .build();

        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError()
                    .entity(ApiResponse.error(
                            "Impossible de créer le compte."
                    ))
                    .build();
        }
    }

    @POST
    @Path("/login")
    public Response login(LoginRequest request) {
        try {
            if (request == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Données manquantes."))
                        .build();
            }

            User user = service.login(request);

            if (user == null) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error(
                                "Identifiants incorrects ou compte inactif."
                        ))
                        .build();
            }

            String token = JwtUtil.create(
                    user.getId(),
                    user.getRole()
            );

            Map<String, Object> data = Map.of(
                    "token", token,
                    "tokenType", "Bearer",
                    "expiresIn", 86400,
                    "user", user
            );

            return Response.ok(
                    ApiResponse.ok("Connexion réussie.", data)
            ).build();

        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError()
                    .entity(ApiResponse.error(
                            "Erreur lors de la connexion."
                    ))
                    .build();
        }
    }
}
