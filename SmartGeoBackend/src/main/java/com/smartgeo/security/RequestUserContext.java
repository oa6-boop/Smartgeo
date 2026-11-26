package com.smartgeo.security;

public final class RequestUserContext {

    private static final ThreadLocal<Long> USER_ID =
            new ThreadLocal<>();

    private static final ThreadLocal<String> USER_ROLE =
            new ThreadLocal<>();

    private RequestUserContext() {
    }

    public static void set(long userId, String role) {
        USER_ID.set(userId);
        USER_ROLE.set(role);
    }

    public static long getUserId() {
        Long id = USER_ID.get();
        return id == null ? -1L : id;
    }

    public static String getRole() {
        String role = USER_ROLE.get();
        return role == null ? "" : role;
    }

    public static void clear() {
        USER_ID.remove();
        USER_ROLE.remove();
    }
}