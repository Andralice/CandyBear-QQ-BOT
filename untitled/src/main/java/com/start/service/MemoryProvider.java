package com.start.service;

import java.util.List;

/** 记忆提供者接口。各 Repository 可实现此接口接入统一记忆查询。 */
public interface MemoryProvider {
    /** 提供者名称，如 "long_term" "user_profile" */
    String name();

    /** 按条件检索记忆 */
    List<MemoryEntry> search(MemoryQuery query);
}
