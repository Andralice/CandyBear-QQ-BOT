package com.start.model;

import java.time.LocalDateTime;

public class UserProfession {
    private Long id;
    private long userId;
    private String groupId;
    private String professionPath;   // 脉系：剑修/法神/刺客/丹道/御兽/佛修/符箓/搞笑
    private String professionName;   // 当前职业名
    private int tier = 1;            // 位阶 1-5
    private String rarity;           // 稀有度：普通/稀有/史诗/传说
    private int combatPower;         // 战力
    private int streakGood = 0;      // 连续好运天数
    private int streakBad = 0;       // 连续霉运天数
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getProfessionPath() { return professionPath; }
    public void setProfessionPath(String professionPath) { this.professionPath = professionPath; }

    public String getProfessionName() { return professionName; }
    public void setProfessionName(String professionName) { this.professionName = professionName; }

    public int getTier() { return tier; }
    public void setTier(int tier) { this.tier = tier; }

    public String getRarity() { return rarity; }
    public void setRarity(String rarity) { this.rarity = rarity; }

    public int getCombatPower() { return combatPower; }
    public void setCombatPower(int combatPower) { this.combatPower = combatPower; }

    public int getStreakGood() { return streakGood; }
    public void setStreakGood(int streakGood) { this.streakGood = streakGood; }

    public int getStreakBad() { return streakBad; }
    public void setStreakBad(int streakBad) { this.streakBad = streakBad; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
