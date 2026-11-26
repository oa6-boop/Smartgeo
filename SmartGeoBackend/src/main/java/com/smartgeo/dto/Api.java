
package com.smartgeo.dto;

import java.util.Map;

public final class Api {

    private Api() {}

    public static Map<String, Object> ok(Object data) {
        return Map.of(
                "success", true,
                "data", data == null ? Map.of() : data
        );
    }

    public static Map<String, Object> error(String message) {
        return Map.of(
                "success", false,
                "message", message
        );
    }
}
