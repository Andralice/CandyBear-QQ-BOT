package com.start.model;

import java.time.LocalDateTime;

public class UserAlias {
    public enum AliasType {
        SUBJECTIVE,  // 用户自称
        OBJECTIVE,   // 他人称呼
        BOT_ALIAS    // 糖果熊自己的别称
    }

    private Long id;
    private String targetUserId;
    private String groupId;
    private String aliasName;
    private AliasType aliasType;
    private String setByUserId;
    private int usageCount;
    private String primaryLocation;
    private String secondaryLocation;
    private LocalDateTime locationUpdatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String v) { this.targetUserId = v; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String v) { this.groupId = v; }
    public String getAliasName() { return aliasName; }
    public void setAliasName(String v) { this.aliasName = v; }
    public AliasType getAliasType() { return aliasType; }
    public void setAliasType(AliasType v) { this.aliasType = v; }
    public String getSetByUserId() { return setByUserId; }
    public void setSetByUserId(String v) { this.setByUserId = v; }
    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int v) { this.usageCount = v; }
    public String getPrimaryLocation() { return primaryLocation; }
    public void setPrimaryLocation(String v) { this.primaryLocation = v; }
    public String getSecondaryLocation() { return secondaryLocation; }
    public void setSecondaryLocation(String v) { this.secondaryLocation = v; }
    public LocalDateTime getLocationUpdatedAt() { return locationUpdatedAt; }
    public void setLocationUpdatedAt(LocalDateTime v) { this.locationUpdatedAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
