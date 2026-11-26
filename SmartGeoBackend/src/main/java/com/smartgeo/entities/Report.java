package com.smartgeo.entities;

import java.time.LocalDateTime;

public class Report {
    private long id, missionId, userId;
    private String title, description, status;
    private LocalDateTime createdAt, updatedAt;

    public long getId(){return id;} public void setId(long v){id=v;}
    public long getMissionId(){return missionId;} public void setMissionId(long v){missionId=v;}
    public long getUserId(){return userId;} public void setUserId(long v){userId=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
