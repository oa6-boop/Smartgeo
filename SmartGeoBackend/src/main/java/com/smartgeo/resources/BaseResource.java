package com.smartgeo.resources;

import com.smartgeo.security.RequestUserContext;
import jakarta.ws.rs.core.Response;

public abstract class BaseResource {

    protected long currentId() {
        return RequestUserContext.getUserId();
    }

    protected String currentRole() {
        return RequestUserContext.getRole();
    }

    protected boolean admin() {
        return "ADMIN".equals(currentRole());
    }

    protected Response forbidden() {
        return Response.status(403)
                .entity(java.util.Map.of(
                        "error", "Accès interdit"
                ))
                .build();
    }
}