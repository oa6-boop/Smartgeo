package com.smartgeo.services;

import com.smartgeo.dao.PositionDAO;
import com.smartgeo.entities.Position;
import java.sql.SQLException;
import java.util.List;

public class PositionService {
    private final PositionDAO dao=new PositionDAO();
    public long save(Position p)throws SQLException{
        if(p==null || p.getLatitude() < -90 || p.getLatitude() > 90 ||
                p.getLongitude() < -180 || p.getLongitude() > 180 || p.getUserId()<=0)
            throw new IllegalArgumentException("Coordonnées GPS ou utilisateur invalides.");
        return dao.create(p);
    }
    public Position latest(long userId)throws SQLException{return dao.latest(userId);}
    public List<Position> history(long userId,int limit)throws SQLException{return dao.history(userId,limit);}
}
