package com.start.vision;

public class ProfessionData {
    public String userId;
    public String professionName;
    public int tier;
    public String tierName;
    public String description;
    public String rarity;
    public int combatPower;

    // 新增
    public String professionPath;   // 脉系
    public int todayLuck;           // 运势 0-100
    public String changeDesc;       // 变化趋势
    public int streakGood;          // 连升天数
    public int streakBad;           // 连降天数
    public int groupRank;           // 群内排名（0=未计算）
    public int bestTier;            // 历史最高位阶
    public String bestTierName;     // 历史最高位阶名

    public ProfessionData(String userId, String professionName, int tier,
                          String tierName, String description, String rarity, int combatPower) {
        this.userId = userId;
        this.professionName = professionName;
        this.tier = tier;
        this.tierName = tierName;
        this.description = description;
        this.rarity = rarity;
        this.combatPower = combatPower;
    }
}
