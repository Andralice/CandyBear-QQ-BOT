package com.start.service;

import com.start.model.ChatUser;
import com.start.repository.UserRepository;
import java.util.Optional;
/**
 * 用户服务类
 * <p>
 * 负责处理与用户相关的业务逻辑，包括用户的创建、查询、更新以及偏好获取等操作。
 * 该类通过 {@link UserRepository} 与数据层进行交互，确保用户数据的持久化和一致性。
 * </p>
 *
 * @author Lingma
 * @version 1.0
 */


public class UserService {
    private final UserRepository userRepo;

    public UserService() {
        this.userRepo = new UserRepository();
    }

    /**
     * 获取或创建用户（整合到AIHandler中）
     */
    public ChatUser getOrCreateUser(String userId, String nickname) {
        var result = userRepo.findUserById(userId);
        if (result.isSuccess()) {
            Optional<ChatUser> existingUser = result.getData();
            if (existingUser.isPresent()) {
                // 更新用户信息
                ChatUser user = existingUser.get();
                if (nickname != null && !nickname.equals(user.getNickname())) {
                    user.setNickname(nickname);
                    userRepo.createOrUpdateUser(userId, nickname);
                }
                return user;
            }
        }

        // 创建新用户
        userRepo.createOrUpdateUser(userId, nickname != null ? nickname : "用户" + userId);
        return new ChatUser(userId, nickname != null ? nickname : "用户" + userId);
    }

    /**
     * 更新用户最后活跃时间
     */
    public void updateUserActivity(String userId) {
        userRepo.incrementMessageCount(userId);
    }

    /**
     * 获取用户偏好（用于AI个性化）
     */
    public java.util.List<String> getUserPreferences(String userId) {
        var result = userRepo.getUserPreferences(userId);
        return result.isSuccess() ? result.getData() : java.util.Collections.emptyList();
    }
}