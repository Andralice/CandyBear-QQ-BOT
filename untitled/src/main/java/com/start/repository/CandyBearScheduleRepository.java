package com.start.repository;

import com.start.model.CandyBearSchedule;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class CandyBearScheduleRepository implements Repository {

    private final DataSource dataSource;

    @Override
    public DataSource getDataSource() { return dataSource; }

    public CandyBearScheduleRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 删除指定周的旧计划 */
    public void deleteWeek(LocalDate monday, LocalDate sunday) throws SQLException {
        String sql = "DELETE FROM candy_bear_schedule WHERE schedule_date BETWEEN ? AND ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(monday));
            ps.setDate(2, Date.valueOf(sunday));
            ps.executeUpdate();
        }
    }

    /** 插入一条日程 */
    public void insert(CandyBearSchedule s) throws SQLException {
        String sql = "INSERT INTO candy_bear_schedule (schedule_date, day_of_week, time_slot, start_time, end_time, activity, location, mood, is_school_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setDate(1, Date.valueOf(s.getScheduleDate()));
            ps.setString(2, s.getDayOfWeek());
            ps.setString(3, s.getTimeSlot());
            ps.setTime(4, Time.valueOf(s.getStartTime()));
            ps.setTime(5, Time.valueOf(s.getEndTime()));
            ps.setString(6, s.getActivity());
            ps.setString(7, s.getLocation());
            ps.setString(8, s.getMood());
            ps.setBoolean(9, s.isSchoolDay());
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) s.setId(keys.getLong(1));
        }
    }

    /** 查当前时间所在的时段活动 */
    public CandyBearSchedule findCurrent(LocalDate today, LocalTime now) throws SQLException {
        String sql = "SELECT * FROM candy_bear_schedule WHERE schedule_date = ? AND start_time <= ? AND end_time >= ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(today));
            ps.setTime(2, Time.valueOf(now));
            ps.setTime(3, Time.valueOf(now));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
            return null;
        }
    }

    /** 查今天的全部日程 */
    public List<CandyBearSchedule> findToday(LocalDate today) throws SQLException {
        String sql = "SELECT * FROM candy_bear_schedule WHERE schedule_date = ? ORDER BY start_time";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(today));
            ResultSet rs = ps.executeQuery();
            List<CandyBearSchedule> list = new ArrayList<>();
            while (rs.next()) list.add(mapRow(rs));
            return list;
        }
    }

    /** 查一周的全部日程 */
    public List<CandyBearSchedule> findWeek(LocalDate monday) throws SQLException {
        String sql = "SELECT * FROM candy_bear_schedule WHERE schedule_date BETWEEN ? AND ? ORDER BY schedule_date, start_time";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(monday));
            ps.setDate(2, Date.valueOf(monday.plusDays(6)));
            ResultSet rs = ps.executeQuery();
            List<CandyBearSchedule> list = new ArrayList<>();
            while (rs.next()) list.add(mapRow(rs));
            return list;
        }
    }

    /** 检查指定日期是否已有日程 */
    public boolean hasSchedule(LocalDate date) throws SQLException {
        String sql = "SELECT COUNT(*) FROM candy_bear_schedule WHERE schedule_date = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private CandyBearSchedule mapRow(ResultSet rs) throws SQLException {
        CandyBearSchedule s = new CandyBearSchedule();
        s.setId(rs.getLong("id"));
        s.setScheduleDate(rs.getDate("schedule_date").toLocalDate());
        s.setDayOfWeek(rs.getString("day_of_week"));
        s.setTimeSlot(rs.getString("time_slot"));
        s.setStartTime(rs.getTime("start_time").toLocalTime());
        s.setEndTime(rs.getTime("end_time").toLocalTime());
        s.setActivity(rs.getString("activity"));
        s.setLocation(rs.getString("location"));
        s.setMood(rs.getString("mood"));
        s.setSchoolDay(rs.getBoolean("is_school_day"));
        return s;
    }
}
