
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.PasswordUtil;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Map;

@Path("/account/password")
@Secured
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AccountSecurityResource extends BaseResource {

    public record PasswordRequest(
            String oldPassword,
            String newPassword
    ) {}

    @PUT
    public Response changePassword(
            PasswordRequest request
    ) {

        if (
                request == null ||
                        request.oldPassword() == null ||
                        request.newPassword() == null
        ) {

            return error(
                    400,
                    "Tous les champs sont obligatoires."
            );
        }

        String oldPassword =
                request.oldPassword();

        String newPassword =
                request.newPassword();

        if (
                newPassword.length() < 8 ||
                        newPassword.isBlank() ||
                        newPassword.getBytes(
                                StandardCharsets.UTF_8
                        ).length > 72
        ) {

            return error(
                    400,
                    "Le nouveau mot de passe doit contenir " +
                            "au moins 8 caracteres et au maximum 72 octets."
            );
        }

        if (oldPassword.equals(newPassword)) {

            return error(
                    400,
                    "Choisissez un mot de passe different."
            );
        }

        try (Connection c = Database.getConnection()) {

            String hash;

            try (
                    PreparedStatement p =
                            c.prepareStatement("""
                                SELECT password_hash
                                FROM users
                                WHERE id=?
                                  AND status='ACTIVE'
                                """)
            ) {

                p.setLong(1, currentId());

                try (ResultSet r = p.executeQuery()) {

                    if (!r.next()) {

                        return error(
                                401,
                                "Compte indisponible."
                        );
                    }

                    hash = r.getString(1);
                }
            }

            if (!PasswordUtil.verify(
                    oldPassword,
                    hash
            )) {

                return error(
                        400,
                        "Ancien mot de passe incorrect."
                );
            }

            String newHash =
                    PasswordUtil.hash(newPassword);

            try (
                    PreparedStatement p =
                            c.prepareStatement("""
                                UPDATE users
                                SET password_hash=?
                                WHERE id=?
                                  AND status='ACTIVE'
                                  AND password_hash=?
                                """)
            ) {

                p.setString(1, newHash);

                p.setLong(2, currentId());

                p.setString(3, hash);

                if (p.executeUpdate() != 1) {

                    return error(
                            409,
                            "Le compte a change. Reessayez."
                    );
                }
            }

            return Response.ok(Map.of(
                    "success", true,
                    "message", "Mot de passe modifie."
            )).build();

        } catch (Exception e) {

            return error(
                    500,
                    "Modification du mot de passe impossible."
            );
        }
    }

    private Response error(
            int status,
            String message
    ) {

        return Response.status(status)
                .entity(Map.of(
                        "success", false,
                        "message", message
                ))
                .build();
    }
}
