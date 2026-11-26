package com.smartgeo.dao;

import com.smartgeo.entities.Message;
import java.sql.*;
import java.util.*;

public class MessageDAO {
    public long create(Message m)throws SQLException{
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO messages(sender_id,receiver_id,content) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)){
            p.setLong(1,m.getSenderId());p.setLong(2,m.getReceiverId());p.setString(3,m.getContent());p.executeUpdate();
            try(ResultSet r=p.getGeneratedKeys()){r.next();return r.getLong(1);}
        }
    }
    public List<Message> conversation(long a,long b)throws SQLException{
        List<Message> out=new ArrayList<>();
        String q="SELECT * FROM messages WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?) ORDER BY sent_at";
        try(Connection c=DatabaseConnection.getConnection();PreparedStatement p=c.prepareStatement(q)){
            p.setLong(1,a);p.setLong(2,b);p.setLong(3,b);p.setLong(4,a);
            try(ResultSet r=p.executeQuery()){while(r.next()){
                Message m=new Message();m.setId(r.getLong("id"));m.setSenderId(r.getLong("sender_id"));m.setReceiverId(r.getLong("receiver_id"));
                m.setContent(r.getString("content"));Timestamp t=r.getTimestamp("sent_at");if(t!=null)m.setSentAt(t.toLocalDateTime());
                Timestamp rd=r.getTimestamp("read_at");if(rd!=null)m.setReadAt(rd.toLocalDateTime());out.add(m);
            }}
        }return out;
    }
}
