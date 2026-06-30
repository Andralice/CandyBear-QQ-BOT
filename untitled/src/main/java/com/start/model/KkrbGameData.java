package com.start.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** kkrb.net 提取的游戏数据模型（特勤处/脑机/密码） */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KkrbGameData {

    public String label;
    public String timestamp;
    public String text;
    public String error;

    // 特勤处 — 各工作台最划算产品
    public List<SwatProduct> products;

    // 密码门
    public List<DoorPassword> passwords;

    // 脑机 — 可扫描物品
    public List<BcicItem> items;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SwatProduct {
        public String workbench;
        public String product;
        public int profit;
        public int idealPrice;
        public String sellTime;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DoorPassword {
        public String map;
        public String password;
        public String updateDate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BcicItem {
        public String name;
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }

    public boolean isEmpty() {
        return (products == null || products.isEmpty())
            && (passwords == null || passwords.isEmpty())
            && (items == null || items.isEmpty())
            && (text == null || text.isEmpty());
    }
}
