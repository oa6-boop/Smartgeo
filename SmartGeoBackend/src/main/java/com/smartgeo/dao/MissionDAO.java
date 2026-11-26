package com.smartgeo.dao;

import com.smartgeo.entities.Mission;
import java.sql.*;
import java.util.*;

public class MissionDAO {
    public long create(Mission m)throws SQLException{
        String q="INSERT INTO missions(title,description,assigned_user_id,created_by,start_date,end_date,status) VALUES(?,?,?,?,?,?,?)";
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement(q,Statement.RETURN_GENERATED_KEYS)){
            p.setString(1,m.getTitle());p.setString(2,m.getDescription());
            if(m.getAssignedUserId()==null)p.setNull(3,Types.BIGINT);else p.setLong(3,m.getAssignedUserId());
            p.setLong(4,m.getCreatedBy());
            if(m.getStartDate()==null)p.setNull(5,Types.TIMESTAMP);else p.setTimestamp(5,Timestamp.valueOf(m.getStartDate()));
            if(m.getEndDate()==null)p.setNull(6,Types.TIMESTAMP);else p.setTimestamp(6,Timestamp.valueOf(m.getEndDate()));
            p.setString(7,m.getStatus()==null?"PENDING":m.getStatus());p.executeUpdate();
            try(ResultSet r=p.getGeneratedKeys()){r.next();return r.getLong(1);}
        }
    }
    public List<Mission> findAll()throws SQLException{
        List<Mission> out=new ArrayList<>();
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM missions ORDER BY created_at DESC");ResultSet r=p.executeQuery()){
            while(r.next()){Mission m=new Mission();m.setId(r.getLong("id"));m.setTitle(r.getString("title"));m.setDescription(r.getString("description"));
                long assigned=r.getLong("assigned_user_id");m.setAssignedUserId(r.wasNull()?null:assigned);m.setCreatedBy(r.getLong("created_by"));
                Timestamp st=r.getTimestamp("start_date");if(st!=null)m.setStartDate(st.toLocalDateTime());
                Timestamp en=r.getTimestamp("end_date");if(en!=null)m.setEndDate(en.toLocalDateTime());
                m.setStatus(r.getString("status"));Timestamp cr=r.getTimestamp("created_at");if(cr!=null)m.setCreatedAt(cr.toLocalDateTime());out.add(m);}
        }return out;
    }
}
