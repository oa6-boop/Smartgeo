
package com.smartgeo.security;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

@Provider
@Secured
@Priority(Priorities.AUTHORIZATION)
public class AdminOperationsFilter
        implements ContainerRequestFilter,
        ContainerResponseFilter {

    private static final Logger LOG =
            Logger.getLogger("SmartGeo.AdminAudit");

    private static final Set<String> WRITE_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    public void filter(
            ContainerRequestContext request
    ) {

        String path =
                request.getUriInfo().getPath();

        String method =
                request.getMethod();

        SecurityContext security =
                request.getSecurityContext();

        boolean admin =
                security != null &&
                        security.isUserInRole("ADMIN");

        // Les anciennes routes de planning doivent
        // egalement respecter les droits ADMIN.

        boolean scheduleWrite =
                (
                        path.equals("schedules") ||
                                path.startsWith("schedules/")
                ) &&
                        WRITE_METHODS.contains(method);

        boolean adminPath =
                path.equals("admin") ||
                        path.startsWith("admin/");

        if ((scheduleWrite || adminPath) && !admin) {

            request.abortWith(
                    Response.status(403)
                            .entity(Map.of(
                                    "success", false,
                                    "message",
                                    "Operation reservee a ADMIN."
                            ))
                            .build()
            );

            return;
        }

        if (
                admin &&
                        security.getUserPrincipal() != null &&
                        WRITE_METHODS.contains(method)
        ) {

            request.setProperty(
                    "smartgeo.audit.admin",
                    security.getUserPrincipal().getName()
            );
        }
    }

    @Override
    public void filter(
            ContainerRequestContext request,
            ContainerResponseContext response
    ) {

        Object adminId =
                request.getProperty(
                        "smartgeo.audit.admin"
                );

        if (adminId == null) {
            return;
        }

        String path =
                request.getUriInfo()
                        .getPath()
                        .replaceAll("[\\r\\n]", "");

        LOG.info(
                "adminId=" + adminId +
                        " method=" + request.getMethod() +
                        " path=" + path +
                        " status=" + response.getStatus()
        );
    }
}
