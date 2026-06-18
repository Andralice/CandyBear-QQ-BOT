package com.start.model;

import java.time.LocalDate;

/** 每日职业变动日志：记录每天的抽职业 + PK 造成的战力变化明细 */
public class ProfessionDailyLog {
    private long userId;
    private String groupId;
    private LocalDate logDate;
    private String professionPath;
    private String professionName;
    private int tier;
    private String rarity;
    private int yesterdayPower;
    private int basePower;
    private int powerFromLuck;
    private int powerFromPk;
    private int finalPower;
    private int luckValue;
    private String changeSummary;

    public long getUserId() { return userId; }
    public void setUserId(long v) { userId = v; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String v) { groupId = v; }
    public LocalDate getLogDate() { return logDate; }
    public void setLogDate(LocalDate v) { logDate = v; }
    public String getProfessionPath() { return professionPath; }
    public void setProfessionPath(String v) { professionPath = v; }
    public String getProfessionName() { return professionName; }
    public void setProfessionName(String v) { professionName = v; }
    public int getTier() { return tier; }
    public void setTier(int v) { tier = v; }
    public String getRarity() { return rarity; }
    public void setRarity(String v) { rarity = v; }
    public int getYesterdayPower() { return yesterdayPower; }
    public void setYesterdayPower(int v) { yesterdayPower = v; }
    public int getBasePower() { return basePower; }
    public void setBasePower(int v) { basePower = v; }
    public int getPowerFromLuck() { return powerFromLuck; }
    public void setPowerFromLuck(int v) { powerFromLuck = v; }
    public int getPowerFromPk() { return powerFromPk; }
    public void setPowerFromPk(int v) { powerFromPk = v; }
    public int getFinalPower() { return finalPower; }
    public void setFinalPower(int v) { finalPower = v; }
    public int getLuckValue() { return luckValue; }
    public void setLuckValue(int v) { luckValue = v; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String v) { changeSummary = v; }
}
