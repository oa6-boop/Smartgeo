
package com.smartgeo.config;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.Set;

@Provider
public class CorsFilter
        implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Set<String> ALLOWED_ORIGINS = Set.of(
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://localhost:5500",
            "http://127.0.0.1:5500",
            "http://localhost:5501",
            "http://127.0.0.1:5501"
    );

    @Override
    public void filter(ContainerRequestContext request)
            throws IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            request.abortWith(Response.noContent().build());
        }
    }

    @Override
    public void filter(
            ContainerRequestContext request,
            ContainerResponseContext response
    ) throws IOException {

        String origin = request.getHeaderString("Origin");

        if (origin == null || !ALLOWED_ORIGINS.contains(origin)) {
            return;
        }

        response.getHeaders().putSingle(
                "Access-Control-Allow-Origin",
                origin
        );

        response.getHeaders().putSingle(
                "Vary",
                "Origin"
        );

        response.getHeaders().putSingle(
                "Access-Control-Allow-Headers",
                "Content-Type, Authorization, Accept"
        );

        response.getHeaders().putSingle(
                "Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, OPTIONS"
        );

        response.getHeaders().putSingle(
                "Access-Control-Max-Age",
                "3600"
        );
    }
}
