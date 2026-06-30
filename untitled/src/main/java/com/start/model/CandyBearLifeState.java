package com.start.model;

import java.time.LocalDate;

/** 糖果熊的长期人生状态，数月不变。 */
public class CandyBearLifeState {
    private Long id;
    private String school = "";
    private String grade = "高二";
    private String friends = "小雨,阿乐";       // 逗号分隔
    private String hobbies = "三角洲行动,洛克王国,崩铁,追番,画画,看小说";
    private String recentProblem = "";
    private String currentGoal = "";
    private String location = "北京";
    private String healthNote = "轻微心脏问题，不需每天上学";
    private LocalDate updatedAt;

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSchool() { return school; }
    public void setSchool(String school) { this.school = school; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getFriends() { return friends; }
    public void setFriends(String friends) { this.friends = friends; }
    public String getHobbies() { return hobbies; }
    public void setHobbies(String hobbies) { this.hobbies = hobbies; }
    public String getRecentProblem() { return recentProblem; }
    public void setRecentProblem(String recentProblem) { this.recentProblem = recentProblem; }
    public String getCurrentGoal() { return currentGoal; }
    public void setCurrentGoal(String currentGoal) { this.currentGoal = currentGoal; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getHealthNote() { return healthNote; }
    public void setHealthNote(String healthNote) { this.healthNote = healthNote; }
    public LocalDate getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDate updatedAt) { this.updatedAt = updatedAt; }
}
