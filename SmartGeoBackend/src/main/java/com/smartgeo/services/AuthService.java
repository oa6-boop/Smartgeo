
package com.smartgeo.services;

import com.smartgeo.dao.UserDAO;
import com.smartgeo.dto.LoginRequest;
import com.smartgeo.entities.User;
import com.smartgeo.security.PasswordUtil;

import java.sql.SQLException;

public class AuthService {

    private final UserDAO userDAO = new UserDAO();

    public User login(LoginRequest request) throws SQLException {

        if (request == null
                || request.getUsername() == null
                || request.getPassword() == null) {
            return null;
        }

        String username = request.getUsername().trim();
        String password = request.getPassword();

        User user = userDAO.findByUsername(username);

        if (user == null) {
            return null;
        }

        String passwordHash = userDAO.findPasswordHash(username);

        if (!PasswordUtil.verify(password, passwordHash)) {
            return null;
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            return null;
        }

        return user;
    }

    public User register(User user, String password)
            throws SQLException {

        if (user == null) {
            throw new IllegalArgumentException("Utilisateur obligatoire.");
        }

        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException(
                    "Le mot de passe doit contenir au moins 8 caractères."
            );
        }

        if (user.getFullName() == null
                || user.getFullName().isBlank()
                || user.getUsername() == null
                || user.getUsername().isBlank()
                || user.getEmail() == null
                || user.getEmail().isBlank()) {

            throw new IllegalArgumentException(
                    "Nom complet, nom utilisateur et email obligatoires."
            );
        }

        String username = user.getUsername().trim();

        if (userDAO.findByUsername(username) != null) {
            throw new IllegalArgumentException(
                    "Ce nom d'utilisateur existe déjà."
            );
        }

        user.setUsername(username);
        user.setRole("USER");
        user.setStatus("ACTIVE");

        String passwordHash = PasswordUtil.hash(password);

        long id = userDAO.create(user, passwordHash);

        user.setId(id);

        return user;
    }
}
