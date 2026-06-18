package com.start;

import com.start.config.DatabaseConfig;
import com.start.repository.UserRepository;
import com.start.repository.MessageRepository;

/**
 * 手动运行：需要 MySQL 连接。mvn exec:java -Dexec.mainClass="com.start.DatabaseTest"
 * 或是用 mvn test 跑新的 JUnit 测试：CommandPolicyTest / LuckUtilTest / DatabaseResultTest
 */
public class DatabaseTest {
    public static void main(String[] args) {
        System.out.println("开始数据库测试...");

        // 测试连接池
        try {
            var conn = DatabaseConfig.getConnection();
            System.out.println("✅ 连接成功");
            conn.close();
        } catch (Exception e) {
            System.err.println("❌ 连接失败: " + e.getMessage());
            return;
        }

        // 测试UserRepository
        UserRepository userRepo = new UserRepository();
        var userResult = userRepo.createOrUpdateUser("test123", "测试用户");
        if (userResult.isSuccess()) {
            System.out.println("✅ UserRepository测试成功");
        } else {
            System.err.println("❌ UserRepository测试失败: " + userResult.getError());
        }

        // 测试MessageRepository
        MessageRepository msgRepo = new MessageRepository();
        var messageData = new java.util.HashMap<String, Object>();
        messageData.put("sessionId", "test_session");
        messageData.put("userId", "test123");
        messageData.put("content", "测试消息");
        messageData.put("isRobotReply", false);

        var msgResult = msgRepo.saveMessage(messageData);
        if (msgResult.isSuccess()) {
            System.out.println("✅ MessageRepository测试成功，消息ID: " + msgResult.getData());
        } else {
            System.err.println("❌ MessageRepository测试失败: " + msgResult.getError());
        }

        System.out.println("数据库测试完成");
    }
}