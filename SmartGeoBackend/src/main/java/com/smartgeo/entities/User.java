package com.smartgeo.entities;

import java.time.LocalDateTime;

public class User {
    private long id;
    private String fullName, username, email, phone, role, status;
    private LocalDateTime createdAt, updatedAt;

    public User() {}
    public User(long id, String fullName, String username, String email,
                String phone, String role, String status,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id=id; this.fullName=fullName; this.username=username;
        this.email=email; this.phone=phone; this.role=role; this.status=status;
        this.createdAt=createdAt; this.updatedAt=updatedAt;
    }
    public long getId(){return id;} public void setId(long v){id=v;}
    public String getFullName(){return fullName;} public void setFullName(String v){fullName=v;}
    public String getUsername(){return username;} public void setUsername(String v){username=v;}
    public String getEmail(){return email;} public void setEmail(String v){email=v;}
    public String getPhone(){return phone;} public void setPhone(String v){phone=v;}
    public String getRole(){return role;} public void setRole(String v){role=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
