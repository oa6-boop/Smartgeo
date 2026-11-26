package com.smartgeo.dao;

import com.smartgeo.database.Database;
import java.sql.Connection;
import java.sql.SQLException;

public final class DatabaseConnection {

    private DatabaseConnection() {
    }

    public static Connection getConnection() throws SQLException {
        return Database.getConnection();
    }
}