package com.smartgeo.entities;

import java.time.LocalDateTime;

public class Message {
    private long id, senderId, receiverId;
    private String content;
    private LocalDateTime sentAt, readAt;

    public long getId(){return id;} public void setId(long v){id=v;}
    public long getSenderId(){return senderId;} public void setSenderId(long v){senderId=v;}
    public long getReceiverId(){return receiverId;} public void setReceiverId(long v){receiverId=v;}
    public String getContent(){return content;} public void setContent(String v){content=v;}
    public LocalDateTime getSentAt(){return sentAt;} public void setSentAt(LocalDateTime v){sentAt=v;}
    public LocalDateTime getReadAt(){return readAt;} public void setReadAt(LocalDateTime v){readAt=v;}
}
