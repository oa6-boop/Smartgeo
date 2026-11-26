
package com.smartgeo.services;

import com.smartgeo.dao.UserDAO;
import com.smartgeo.entities.User;

import java.sql.SQLException;
import java.util.List;

public class UserService {

    private final UserDAO dao = new UserDAO();

    public List<User> all() throws SQLException {
        return dao.findAll();
    }

    public User one(long id) throws SQLException {
        return dao.findById(id);
    }

    public User create(User user, String password)
            throws SQLException {
        return new AuthService().register(user, password);
    }

    public boolean update(User user) throws SQLException {
        return dao.update(user);
    }

    public boolean delete(long id) throws SQLException {
        return dao.delete(id);
    }
}
