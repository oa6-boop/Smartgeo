package com.smartgeo.dao;

import com.smartgeo.entities.User;
import java.sql.*;
import java.util.*;

public class UserDAO {
    private User map(ResultSet r) throws SQLException {
        User u = new User();
        u.setId(r.getLong("id")); u.setFullName(r.getString("full_name"));
        u.setUsername(r.getString("username")); u.setEmail(r.getString("email"));
        u.setPhone(r.getString("phone")); u.setRole(r.getString("role"));
        u.setStatus(r.getString("status"));
        Timestamp c=r.getTimestamp("created_at"); if(c!=null)u.setCreatedAt(c.toLocalDateTime());
        Timestamp up=r.getTimestamp("updated_at"); if(up!=null)u.setUpdatedAt(up.toLocalDateTime());
        return u;
    }
    public User findByUsername(String username) throws SQLException {
        String sql="SELECT * FROM users WHERE username=?";
        try(Connection c=DatabaseConnection.getConnection(); PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,username); try(ResultSet r=p.executeQuery()){return r.next()?map(r):null;}
        }
    }
    public String findPasswordHash(String username) throws SQLException {
        try(Connection c=DatabaseConnection.getConnection(); PreparedStatement p=c.prepareStatement("SELECT password_hash FROM users WHERE username=?")){
            p.setString(1,username); try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}
        }
    }
    public List<User> findAll() throws SQLException {
        List<User> list=new ArrayList<>();
        try(Connection c=DatabaseConnection.getConnection(); PreparedStatement p=c.prepareStatement("SELECT * FROM users ORDER BY id DESC"); ResultSet r=p.executeQuery()){
            while(r.next())list.add(map(r));
        } return list;
    }
    public User findById(long id) throws SQLException {
        try(Connection c=DatabaseConnection.getConnection(); PreparedStatement p=c.prepareStatement("SELECT * FROM users WHERE id=?")){
            p.setLong(1,id); try(ResultSet r=p.executeQuery()){return r.next()?map(r):null;}
        }
    }
    public long create(User u, String passwordHash) throws SQLException {
        String sql="INSERT INTO users(full_name,username,email,phone,password_hash,role,status) VALUES(?,?,?,?,?,?,?)";
        try(Connection c=DatabaseConnection.getConnection(); PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){
            p.setString(1,u.getFullName()); p.setString(2,u.getUsername()); p.setString(3,u.getEmail());
            p.setString(4,u.getPhone()); p.setString(5,passwordHash);
            p.setString(6,u.getRole()==null?"USER":u.getRole()); p.setString(7,u.getStatus()==null?"ACTIVE":u.getStatus());
            p.executeUpdate(); try(ResultSet r=p.getGeneratedKeys()){r.next();return r.getLong(1);}
        }
    }
    public boolean update(User u) throws SQLException {
        String sql="UPDATE users SET full_name=?,email=?,phone=?,role=?,status=? WHERE id=?";
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,u.getFullName());p.setString(2,u.getEmail());p.setString(3,u.getPhone());
            p.setString(4,u.getRole());p.setString(5,u.getStatus());p.setLong(6,u.getId());
            return p.executeUpdate()>0;
        }
    }
    public boolean delete(long id) throws SQLException {
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement("DELETE FROM users WHERE id=?")){
            p.setLong(1,id);return p.executeUpdate()>0;
        }
    }
}
