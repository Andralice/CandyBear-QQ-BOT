package com.start.repository;

import com.start.model.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 糖果熊人生引擎的数据访问层：story_arc, weekly_diary, daily_journal */
public class CandyBearLifeRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public CandyBearLifeRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ===== Story Arc =====
    public Optional<CandyBearStoryArc> findActiveArc() throws SQLException {
        String sql = "SELECT * FROM candy_bear_story_arcs WHERE active = TRUE ORDER BY start_date DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapArc(rs));
            return Optional.empty();
        }
    }

    public void insertArc(CandyBearStoryArc a) throws SQLException {
        String sql = "INSERT INTO candy_bear_story_arcs (arc_name, start_date, end_date, summary, major_events, mood_trend, active) VALUES (?,?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, a.getArcName()); ps.setDate(2, Date.valueOf(a.getStartDate()));
            ps.setDate(3, Date.valueOf(a.getEndDate())); ps.setString(4, a.getSummary());
            ps.setString(5, a.getMajorEvents()); ps.setString(6, a.getMoodTrend());
            ps.setBoolean(7, a.isActive());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) a.setId(keys.getLong(1));
        }
    }

    public void deactivateArc(long id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("UPDATE candy_bear_story_arcs SET active=FALSE WHERE id=?")) {
            ps.setLong(1, id); ps.executeUpdate();
        }
    }

    // ===== Weekly Diary =====
    public Optional<CandyBearWeeklyDiary> findLatestWeek() throws SQLException {
        String sql = "SELECT * FROM candy_bear_weekly_diaries ORDER BY week_start DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapWeek(rs));
            return Optional.empty();
        }
    }

    public List<CandyBearWeeklyDiary> findRecentWeeks(int count) throws SQLException {
        String sql = "SELECT * FROM candy_bear_weekly_diaries ORDER BY week_start DESC LIMIT ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, count);
            ResultSet rs = ps.executeQuery();
            List<CandyBearWeeklyDiary> list = new ArrayList<>();
            while (rs.next()) list.add(mapWeek(rs));
            return list;
        }
    }

    public void insertWeeklyDiary(CandyBearWeeklyDiary d) throws SQLException {
        String sql = "INSERT INTO candy_bear_weekly_diaries (week_start, week_end, summary, major_events, emotion, next_week_plan) VALUES (?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setDate(1, Date.valueOf(d.getWeekStart())); ps.setDate(2, Date.valueOf(d.getWeekEnd()));
            ps.setString(3, d.getSummary()); ps.setString(4, d.getMajorEvents());
            ps.setString(5, d.getEmotion()); ps.setString(6, d.getNextWeekPlan());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) d.setId(keys.getLong(1));
        }
    }

    public boolean hasWeeklyDiary(LocalDate weekStart) throws SQLException {
        String sql = "SELECT COUNT(*) FROM candy_bear_weekly_diaries WHERE week_start=? AND summary IS NOT NULL AND summary != ''";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(weekStart));
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    // ===== Daily Journal =====
    public Optional<CandyBearDailyJournal> findJournal(LocalDate date) throws SQLException {
        String sql = "SELECT * FROM candy_bear_daily_journals WHERE journal_date = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapJournal(rs));
            return Optional.empty();
        }
    }

    public List<CandyBearDailyJournal> findRecentJournals(int days) throws SQLException {
        String sql = "SELECT * FROM candy_bear_daily_journals ORDER BY journal_date DESC LIMIT ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, days);
            ResultSet rs = ps.executeQuery();
            List<CandyBearDailyJournal> list = new ArrayList<>();
            while (rs.next()) list.add(mapJournal(rs));
            return list;
        }
    }

    public void insertJournal(CandyBearDailyJournal j) throws SQLException {
        String sql = "INSERT INTO candy_bear_daily_journals (journal_date, important_events, emotion, summary) VALUES (?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setDate(1, Date.valueOf(j.getJournalDate()));
            ps.setString(2, j.getImportantEvents()); ps.setString(3, j.getEmotion());
            ps.setString(4, j.getSummary());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) j.setId(keys.getLong(1));
        }
    }

    public boolean hasJournal(LocalDate date) throws SQLException {
        String sql = "SELECT COUNT(*) FROM candy_bear_daily_journals WHERE journal_date=? AND summary IS NOT NULL AND summary != ''";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    // ===== mappers =====
    private CandyBearStoryArc mapArc(ResultSet rs) throws SQLException {
        CandyBearStoryArc a = new CandyBearStoryArc();
        a.setId(rs.getLong("id")); a.setArcName(rs.getString("arc_name"));
        a.setStartDate(rs.getDate("start_date").toLocalDate());
        a.setEndDate(rs.getDate("end_date").toLocalDate());
        a.setSummary(rs.getString("summary")); a.setMajorEvents(rs.getString("major_events"));
        a.setMoodTrend(rs.getString("mood_trend")); a.setActive(rs.getBoolean("active"));
        return a;
    }
    private CandyBearWeeklyDiary mapWeek(ResultSet rs) throws SQLException {
        CandyBearWeeklyDiary d = new CandyBearWeeklyDiary();
        d.setId(rs.getLong("id")); d.setWeekStart(rs.getDate("week_start").toLocalDate());
        d.setWeekEnd(rs.getDate("week_end").toLocalDate()); d.setSummary(rs.getString("summary"));
        d.setMajorEvents(rs.getString("major_events")); d.setEmotion(rs.getString("emotion"));
        d.setNextWeekPlan(rs.getString("next_week_plan"));
        d.setCreatedAt(rs.getDate("created_at") != null ? rs.getDate("created_at").toLocalDate() : null);
        return d;
    }
    private CandyBearDailyJournal mapJournal(ResultSet rs) throws SQLException {
        CandyBearDailyJournal j = new CandyBearDailyJournal();
        j.setId(rs.getLong("id")); j.setJournalDate(rs.getDate("journal_date").toLocalDate());
        j.setImportantEvents(rs.getString("important_events")); j.setEmotion(rs.getString("emotion"));
        j.setSummary(rs.getString("summary"));
        j.setCreatedAt(rs.getDate("created_at") != null ? rs.getDate("created_at").toLocalDate() : null);
        return j;
    }

    // ===== LifeState (single-row table) =====

    /** 查当前 LifeState（永远只有一行，取最新的） */
    public Optional<CandyBearLifeState> findLifeState() throws SQLException {
        String sql = "SELECT * FROM candy_bear_life_state ORDER BY id DESC LIMIT 1";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapLifeState(rs));
            return Optional.empty();
        }
    }

    /** UPDATE 现有行 或 INSERT 新行 */
    public void upsertLifeState(CandyBearLifeState state) throws SQLException {
        Optional<CandyBearLifeState> existing = findLifeState();
        if (existing.isPresent()) {
            String sql = "UPDATE candy_bear_life_state SET school=?, grade=?, friends=?, hobbies=?, recent_problem=?, current_goal=?, location=?, health_note=?, updated_at=? WHERE id=?";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, state.getSchool()); ps.setString(2, state.getGrade());
                ps.setString(3, state.getFriends()); ps.setString(4, state.getHobbies());
                ps.setString(5, state.getRecentProblem()); ps.setString(6, state.getCurrentGoal());
                ps.setString(7, state.getLocation()); ps.setString(8, state.getHealthNote());
                ps.setDate(9, Date.valueOf(state.getUpdatedAt()));
                ps.setLong(10, existing.get().getId());
                ps.executeUpdate();
            }
        } else {
            String sql = "INSERT INTO candy_bear_life_state (school, grade, friends, hobbies, recent_problem, current_goal, location, health_note, updated_at) VALUES (?,?,?,?,?,?,?,?,?)";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, state.getSchool()); ps.setString(2, state.getGrade());
                ps.setString(3, state.getFriends()); ps.setString(4, state.getHobbies());
                ps.setString(5, state.getRecentProblem()); ps.setString(6, state.getCurrentGoal());
                ps.setString(7, state.getLocation()); ps.setString(8, state.getHealthNote());
                ps.setDate(9, Date.valueOf(state.getUpdatedAt()));
                ps.executeUpdate();
            }
        }
    }

    /** 表空时插入默认 LifeState */
    public void initDefaultLifeState() throws SQLException {
        if (findLifeState().isEmpty()) {
            CandyBearLifeState def = new CandyBearLifeState();
            def.setUpdatedAt(LocalDate.now());
            upsertLifeState(def);
        }
    }

    private CandyBearLifeState mapLifeState(ResultSet rs) throws SQLException {
        CandyBearLifeState s = new CandyBearLifeState();
        s.setSchool(rs.getString("school"));
        s.setGrade(rs.getString("grade"));
        s.setFriends(rs.getString("friends"));
        s.setHobbies(rs.getString("hobbies"));
        s.setRecentProblem(rs.getString("recent_problem"));
        s.setCurrentGoal(rs.getString("current_goal"));
        s.setLocation(rs.getString("location"));
        s.setHealthNote(rs.getString("health_note"));
        s.setUpdatedAt(rs.getDate("updated_at") != null ? rs.getDate("updated_at").toLocalDate() : LocalDate.now());
        return s;
    }
}
