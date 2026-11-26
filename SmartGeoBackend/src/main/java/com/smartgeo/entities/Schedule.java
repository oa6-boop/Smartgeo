package com.smartgeo.entities;

import java.time.LocalTime;

public class Schedule {
    private long id, userId;
    private LocalTime startTime, endTime;
    private int frequencySeconds;
    private String timezone;
    private boolean enabled;

    public long getId(){return id;} public void setId(long v){id=v;}
    public long getUserId(){return userId;} public void setUserId(long v){userId=v;}
    public LocalTime getStartTime(){return startTime;} public void setStartTime(LocalTime v){startTime=v;}
    public LocalTime getEndTime(){return endTime;} public void setEndTime(LocalTime v){endTime=v;}
    public int getFrequencySeconds(){return frequencySeconds;} public void setFrequencySeconds(int v){frequencySeconds=v;}
    public String getTimezone(){return timezone;} public void setTimezone(String v){timezone=v;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
}
