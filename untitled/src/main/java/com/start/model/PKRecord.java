package com.start.model;

import java.time.LocalDate;

public class PKRecord {
    private long attackerId;
    private long defenderId;
    private String groupId;
    private int attackerTier;
    private int defenderTier;
    private boolean win;
    private int powerChange;
    private boolean isBully;
    private LocalDate pkDate;

    public long getAttackerId() { return attackerId; }
    public void setAttackerId(long v) { attackerId = v; }
    public long getDefenderId() { return defenderId; }
    public void setDefenderId(long v) { defenderId = v; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String v) { groupId = v; }
    public int getAttackerTier() { return attackerTier; }
    public void setAttackerTier(int v) { attackerTier = v; }
    public int getDefenderTier() { return defenderTier; }
    public void setDefenderTier(int v) { defenderTier = v; }
    public boolean isWin() { return win; }
    public void setWin(boolean v) { win = v; }
    public int getPowerChange() { return powerChange; }
    public void setPowerChange(int v) { powerChange = v; }
    public boolean isBully() { return isBully; }
    public void setBully(boolean v) { isBully = v; }
    public LocalDate getPkDate() { return pkDate; }
    public void setPkDate(LocalDate v) { pkDate = v; }
}
