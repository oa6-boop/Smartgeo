
package com.smartgeo.resources;

import com.smartgeo.database.Database;
import com.smartgeo.security.Secured;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.sql.*;
import java.util.*;

@Path("/admin/dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class DashboardResource extends BaseResource {

    @GET
    public Response dashboard() {

        if (!admin()) return forbidden();

        String sql =
                "SELECT " +
                        "(SELECT COUNT(*) FROM users) users_total, " +
                        "(SELECT COUNT(*) FROM users WHERE status='ACTIVE') users_active, " +
                        "(SELECT COUNT(DISTINCT user_id) FROM positions " +
                        " WHERE received_at >= NOW() - INTERVAL 15 MINUTE) sharing_now, " +
                        "(SELECT COUNT(*) FROM messages WHERE read_at IS NULL) unread_messages, " +
                        "(SELECT COUNT(*) FROM location_requests WHERE status='PENDING') pending_requests";

        try (
                Connection c = Database.getConnection();
                PreparedStatement p = c.prepareStatement(sql);
                ResultSet r = p.executeQuery()
        ) {
            r.next();

            Map<String, Object> result = new LinkedHashMap<>();

            for (String key : List.of(
                    "users_total",
                    "users_active",
                    "sharing_now",
                    "unread_messages",
                    "pending_requests"
            )) {
                result.put(key, r.getLong(key));
            }

            return Response.ok(result).build();

        } catch (Exception e) {
            return Response.serverError().build();
        }
    }
}
