package com.start.repository;

import com.start.model.PKRecord;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PKRepository {

    private final DataSource ds;

    public PKRepository(DataSource ds) { this.ds = ds; }

    /** 今日某用户 PK 次数 */
    public int todayPKCount(long userId) {
        String sql = "SELECT COUNT(*) FROM pk_records WHERE attacker_id=? AND pk_date=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setDate(2, Date.valueOf(LocalDate.now()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    /** 今日某用户的欺凌次数 */
    public int todayBullyCount(long userId) {
        String sql = "SELECT COUNT(*) FROM pk_records WHERE attacker_id=? AND pk_date=? AND is_bully=TRUE";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setDate(2, Date.valueOf(LocalDate.now()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public void insert(PKRecord r) {
        String sql = "INSERT INTO pk_records (attacker_id, defender_id, group_id, attacker_tier, defender_tier, win, power_change, is_bully, pk_date) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, r.getAttackerId());
            ps.setLong(2, r.getDefenderId());
            ps.setString(3, r.getGroupId());
            ps.setInt(4, r.getAttackerTier());
            ps.setInt(5, r.getDefenderTier());
            ps.setBoolean(6, r.isWin());
            ps.setInt(7, r.getPowerChange());
            ps.setBoolean(8, r.isBully());
            ps.setDate(9, Date.valueOf(r.getPkDate()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("insert pk_record failed", e);
        }
    }

    /** 今日被某人 PK 过的目标列表（防止同一天反复PK同一个人） */
    public List<Long> todayTargets(long attackerId) {
        List<Long> list = new ArrayList<>();
        String sql = "SELECT defender_id FROM pk_records WHERE attacker_id=? AND pk_date=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, attackerId);
            ps.setDate(2, Date.valueOf(LocalDate.now()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getLong("defender_id"));
            }
        } catch (SQLException ignored) {}
        return list;
    }
}
