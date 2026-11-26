package com.smartgeo.dao;

import com.smartgeo.entities.Position;
import java.sql.*;
import java.util.*;

public class PositionDAO {
    private Position map(ResultSet r)throws SQLException{
        Position p=new Position();p.setId(r.getLong("id"));p.setUserId(r.getLong("user_id"));
        p.setLatitude(r.getDouble("latitude"));p.setLongitude(r.getDouble("longitude"));
        p.setAccuracy((Float)r.getObject("accuracy",Float.class));p.setSpeed((Float)r.getObject("speed",Float.class));
        p.setBearing((Float)r.getObject("bearing",Float.class));p.setBatteryLevel((Integer)r.getObject("battery_level",Integer.class));
        Timestamp t=r.getTimestamp("recorded_at");if(t!=null)p.setRecordedAt(t.toLocalDateTime());
        Timestamp rec=r.getTimestamp("received_at");if(rec!=null)p.setReceivedAt(rec.toLocalDateTime());return p;
    }
    public long create(Position p)throws SQLException{
        String q="INSERT INTO positions(user_id,latitude,longitude,accuracy,speed,bearing,battery_level,recorded_at) VALUES(?,?,?,?,?,?,?,?)";
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement(q,Statement.RETURN_GENERATED_KEYS)){
            s.setLong(1,p.getUserId());s.setDouble(2,p.getLatitude());s.setDouble(3,p.getLongitude());
            if(p.getAccuracy()==null)s.setNull(4,Types.FLOAT);else s.setFloat(4,p.getAccuracy());
            if(p.getSpeed()==null)s.setNull(5,Types.FLOAT);else s.setFloat(5,p.getSpeed());
            if(p.getBearing()==null)s.setNull(6,Types.FLOAT);else s.setFloat(6,p.getBearing());
            if(p.getBatteryLevel()==null)s.setNull(7,Types.INTEGER);else s.setInt(7,p.getBatteryLevel());
            s.setTimestamp(8,Timestamp.valueOf(p.getRecordedAt()==null?java.time.LocalDateTime.now():p.getRecordedAt()));
            s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){r.next();return r.getLong(1);}
        }
    }
    public List<Position> history(long userId,int limit)throws SQLException{
        List<Position> list=new ArrayList<>();
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM positions WHERE user_id=? ORDER BY recorded_at DESC LIMIT ?")){
            s.setLong(1,userId);s.setInt(2,Math.max(1,Math.min(limit,1000)));
            try(ResultSet r=s.executeQuery()){while(r.next())list.add(map(r));}
        }return list;
    }
    public Position latest(long userId)throws SQLException{
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement s=c.prepareStatement("SELECT * FROM positions WHERE user_id=? ORDER BY recorded_at DESC LIMIT 1")){
            s.setLong(1,userId);try(ResultSet r=s.executeQuery()){return r.next()?map(r):null;}
        }
    }
}
