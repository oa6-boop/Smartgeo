package com.smartgeo.security;

import com.smartgeo.dao.UserDAO;
import com.smartgeo.entities.User;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

@Provider
@Secured
@Priority(Priorities.AUTHENTICATION)
public class JwtFilter implements ContainerRequestFilter {
    @Context private ResourceInfo resourceInfo;
    private final UserDAO userDAO = new UserDAO();

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        RequestUserContext.clear();
        String header = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            abort(ctx, 401, "Token manquant.");
            return;
        }
        try {
            var claims = JwtUtil.parse(header.substring(7).trim());
            long id = Long.parseLong(claims.getSubject());
            String tokenRole = claims.get("role", String.class);
            if (id <= 0 || !"ADMIN".equals(tokenRole) && !"USER".equals(tokenRole)) {
                abort(ctx, 401, "Token invalide.");
                return;
            }
            // Verifier les droits actuels en base a chaque requete protegee.
            User user = userDAO.findById(id);
            if (user == null || !"ACTIVE".equals(user.getStatus())
                    || !tokenRole.equals(user.getRole())) {
                abort(ctx, 401, "Compte inactif ou droits modifies. Reconnectez-vous.");
                return;
            }
            SecurityContext previous = ctx.getSecurityContext();
            ctx.setSecurityContext(new SecurityContext() {
                @Override public java.security.Principal getUserPrincipal() {
                    return () -> Long.toString(id);
                }
                @Override public boolean isUserInRole(String role) {
                    return role != null && role.equals(tokenRole);
                }
                @Override public boolean isSecure() {
                    return previous != null && previous.isSecure();
                }
                @Override public String getAuthenticationScheme() { return "Bearer"; }
            });
            if (!hasRequiredRole(tokenRole)) {
                abort(ctx, 403, "Acces interdit pour ce role.");
                return;
            }
            RequestUserContext.set(id, tokenRole);
        } catch (java.sql.SQLException e) {
            RequestUserContext.clear();
            abort(ctx, 503, "Base de donnees indisponible.");
        } catch (Exception e) {
            RequestUserContext.clear();
            abort(ctx, 401, "Token invalide ou expire.");
        }
    }

    private boolean hasRequiredRole(String role) {
        if (resourceInfo == null) return true;
        Method method = resourceInfo.getResourceMethod();
        Class<?> resourceClass = resourceInfo.getResourceClass();
        Secured secured = method == null ? null : method.getAnnotation(Secured.class);
        if (secured == null && resourceClass != null) secured = resourceClass.getAnnotation(Secured.class);
        return secured == null || secured.roles().length == 0
                || Arrays.asList(secured.roles()).contains(role);
    }

    private void abort(ContainerRequestContext ctx, int status, String message) {
        ctx.abortWith(Response.status(status)
                .entity(Map.of("success", false, "message", message)).build());
    }
}
