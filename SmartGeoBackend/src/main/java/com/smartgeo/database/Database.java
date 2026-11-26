package com.smartgeo.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {

    private Database() {
    }

    public static Connection getConnection() throws SQLException {

        String url = System.getenv("SMARTGEO_DB_URL");
        String user = System.getenv("SMARTGEO_DB_USER");
        String password = System.getenv("SMARTGEO_DB_PASSWORD");

        if (url == null || url.isBlank()) {
            throw new SQLException(
                    "SMARTGEO_DB_URL n'est pas configurée."
            );
        }

        if (user == null || user.isBlank()) {
            throw new SQLException(
                    "SMARTGEO_DB_USER n'est pas configurée."
            );
        }

        if (password == null || password.isBlank()) {
            throw new SQLException(
                    "SMARTGEO_DB_PASSWORD n'est pas configurée."
            );
        }

        return DriverManager.getConnection(url, user, password);
    }

    public static long insert(String sql, Binder binder)
            throws SQLException {

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     sql,
                     Statement.RETURN_GENERATED_KEYS
             )) {

            binder.bind(statement);
            statement.executeUpdate();

            try (ResultSet result = statement.getGeneratedKeys()) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
        }

        return -1;
    }

    public static int update(String sql, Binder binder)
            throws SQLException {

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            binder.bind(statement);
            return statement.executeUpdate();
        }
    }

    @FunctionalInterface
    public interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}