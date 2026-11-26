package com.smartgeo.entities;

import java.time.LocalDateTime;

public class Position {
    private long id, userId;
    private double latitude, longitude;
    private Float accuracy, speed, bearing;
    private Integer batteryLevel;
    private LocalDateTime recordedAt, receivedAt;

    public long getId(){return id;} public void setId(long v){id=v;}
    public long getUserId(){return userId;} public void setUserId(long v){userId=v;}
    public double getLatitude(){return latitude;} public void setLatitude(double v){latitude=v;}
    public double getLongitude(){return longitude;} public void setLongitude(double v){longitude=v;}
    public Float getAccuracy(){return accuracy;} public void setAccuracy(Float v){accuracy=v;}
    public Float getSpeed(){return speed;} public void setSpeed(Float v){speed=v;}
    public Float getBearing(){return bearing;} public void setBearing(Float v){bearing=v;}
    public Integer getBatteryLevel(){return batteryLevel;} public void setBatteryLevel(Integer v){batteryLevel=v;}
    public LocalDateTime getRecordedAt(){return recordedAt;} public void setRecordedAt(LocalDateTime v){recordedAt=v;}
    public LocalDateTime getReceivedAt(){return receivedAt;} public void setReceivedAt(LocalDateTime v){receivedAt=v;}
}
