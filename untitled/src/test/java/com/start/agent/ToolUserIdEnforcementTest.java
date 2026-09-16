package com.start.agent;

import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具 user_id / group_id 防伪测试。
 *
 * 验证：即使 LLM 在 args 里塞 user_id="FAKE-XXX"，工具仍以构造函数注入的
 * currentUserId / currentGroupId 为准写入数据库。
 *
 * 不需要真实 DB：用 SpyLongTermMemoryRepository 捕获 insert 时的对象。
 */
class ToolUserIdEnforcementTest {

    /** Spy repo：截留 insert 调用，不连真实 DB。super(null) 不会立即 NPE（dataSource 仅在用到时取连接）。 */
    static class SpyLongTermMemoryRepository extends LongTermMemoryRepository {
        final AtomicReference<LongTermMemory> captured = new AtomicReference<>();
        SpyLongTermMemoryRepository() {
            super(null);
        }
        @Override
        public List<LongTermMemory> findRecentByUser(String userId, String groupId, int limit) {
            return Collections.emptyList();
        }
        @Override
        public void insert(LongTermMemory m) {
            captured.set(m);
        }
        @Override
        public void insertGrounded(LongTermMemory m, java.time.LocalDateTime cutoff) {
            captured.set(m);
        }
    }

    @Test
    void rememberFactIgnoresArgsUserIdAndGroupId() {
        SpyLongTermMemoryRepository spy = new SpyLongTermMemoryRepository();
        RememberFactTool tool = new RememberFactTool(spy, "realUser123", "realGroup456");

        Map<String, Object> args = new HashMap<>();
        args.put("content", "今天吃了火锅");
        args.put("source_message_id", "17");
        args.put("user_id", "FAKE-999");
        args.put("group_id", "FAKE-GROUP-888");
        args.put("memory_type", "fact");

        String result = tool.execute(args);
        assertTrue(result.startsWith("已记住"), "应成功写入，实际=" + result);

        LongTermMemory captured = spy.captured.get();
        assertNotNull(captured, "insert 应被调用");
        assertEquals("realUser123", captured.getUserId(), "userId 应为构造时注入值，不应被 args.user_id 覆盖");
        assertEquals("realGroup456", captured.getGroupId(), "groupId 应为构造时注入值");
        assertEquals("今天吃了火锅", captured.getContent());
    }

    @Test
    void rememberFactRejectsEmptyContent() {
        SpyLongTermMemoryRepository spy = new SpyLongTermMemoryRepository();
        RememberFactTool tool = new RememberFactTool(spy, "u1", "g1");

        Map<String, Object> args = new HashMap<>();
        args.put("user_id", "FAKE-999");
        args.put("group_id", "FAKE-GROUP");
        // 故意没 content

        String result = tool.execute(args);
        assertTrue(result.startsWith("缺少"), "空 content 应被拒绝");
    }
}
