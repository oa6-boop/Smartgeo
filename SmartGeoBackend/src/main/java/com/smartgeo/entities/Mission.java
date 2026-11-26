package com.smartgeo.entities;

import java.time.LocalDateTime;

public class Mission {
    private long id, createdBy;
    private Long assignedUserId;
    private String title, description, status;
    private LocalDateTime startDate, endDate, createdAt;

    public long getId(){return id;} public void setId(long v){id=v;}
    public long getCreatedBy(){return createdBy;} public void setCreatedBy(long v){createdBy=v;}
    public Long getAssignedUserId(){return assignedUserId;} public void setAssignedUserId(Long v){assignedUserId=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getStartDate(){return startDate;} public void setStartDate(LocalDateTime v){startDate=v;}
    public LocalDateTime getEndDate(){return endDate;} public void setEndDate(LocalDateTime v){endDate=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
