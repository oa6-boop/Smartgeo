
package com.smartgeo.resources;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.util.Map;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class TestResource {

    @GET
    public Map<String, Object> health() {

        return Map.of(
                "application", "SmartGeo API",
                "status", "UP"
        );
    }
}
