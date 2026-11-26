package com.smartgeo.dao;

import com.smartgeo.entities.Schedule;
import java.sql.*;
import java.util.*;

public class ScheduleDAO {
    public long create(Schedule s)throws SQLException{
        String q="INSERT INTO schedules(user_id,start_time,end_time,frequency_seconds,timezone,enabled) VALUES(?,?,?,?,?,?)";
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement(q,Statement.RETURN_GENERATED_KEYS)){
            p.setLong(1,s.getUserId());p.setTime(2,Time.valueOf(s.getStartTime()));p.setTime(3,Time.valueOf(s.getEndTime()));
            p.setInt(4,s.getFrequencySeconds());p.setString(5,s.getTimezone()==null?"Africa/Casablanca":s.getTimezone());p.setBoolean(6,s.isEnabled());
            p.executeUpdate();try(ResultSet r=p.getGeneratedKeys()){r.next();return r.getLong(1);}
        }
    }
    public List<Schedule> findByUser(long userId)throws SQLException{
        List<Schedule> out=new ArrayList<>();
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM schedules WHERE user_id=? ORDER BY id DESC")){
            p.setLong(1,userId);try(ResultSet r=p.executeQuery()){while(r.next()){
                Schedule s=new Schedule();s.setId(r.getLong("id"));s.setUserId(r.getLong("user_id"));
                s.setStartTime(r.getTime("start_time").toLocalTime());s.setEndTime(r.getTime("end_time").toLocalTime());
                s.setFrequencySeconds(r.getInt("frequency_seconds"));s.setTimezone(r.getString("timezone"));s.setEnabled(r.getBoolean("enabled"));out.add(s);
            }}
        }return out;
    }
    public boolean setEnabled(long id,boolean enabled)throws SQLException{
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement("UPDATE schedules SET enabled=? WHERE id=?")){
            p.setBoolean(1,enabled);p.setLong(2,id);return p.executeUpdate()>0;
        }
    }
}
