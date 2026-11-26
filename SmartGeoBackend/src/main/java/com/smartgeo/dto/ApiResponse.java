package com.smartgeo.dto;

import java.util.HashMap;
import java.util.Map;

public final class ApiResponse {

    private ApiResponse() {
    }

    public static Map<String, Object> ok(String message, Object data) {
        Map<String, Object> response = new HashMap<>();

        response.put("success", true);
        response.put("message", message);
        response.put("data", data == null ? Map.of() : data);

        return response;
    }

    public static Map<String, Object> error(String message) {
        Map<String, Object> response = new HashMap<>();

        response.put("success", false);
        response.put("message", message);

        return response;
    }
}