package com.start.util;

/**
 * 数据库错误处理工具类
 * <p>
 * 提供统一的数据库异常捕获与处理机制，将底层技术异常转换为对用户友好的提示信息。
 * 同时提供数据库连接状态的检测功能，用于健康检查或前置校验。
 * </p>
 *
 * @author Lingma
 * @version 1.0
 */
public class DatabaseErrorHandler {

    /**
     * 处理数据库错误并返回用户友好的消息
     */
    public static String handleDatabaseError(Exception e) {
        String errorMsg = e.getMessage();

        if (errorMsg.contains("Communications link failure")) {
            return "数据库连接失败，请检查网络或SSH隧道";
        } else if (errorMsg.contains("Access denied")) {
            return "数据库权限错误，请检查用户名密码";
        } else if (errorMsg.contains("Unknown database")) {
            return "数据库不存在，请创建数据库: candybear_db";
        } else if (errorMsg.contains("Table") && errorMsg.contains("doesn't exist")) {
            return "数据库表不存在，请执行建表SQL";
        } else if (errorMsg.contains("Too many connections")) {
            return "数据库连接数过多，请稍后重试";
        } else {
            return "数据库错误: " + errorMsg;
        }
    }

    /**
     * 检查数据库连接状态
     */
    public static boolean isDatabaseAvailable() {
        try {
            // 简单的连接测试
            var conn = com.start.config.DatabaseConfig.getConnection();
            boolean valid = conn.isValid(2);
            conn.close();
            return valid;
        } catch (Exception e) {
            return false;
        }
    }
}