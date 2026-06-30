package com.start.runtime;

/** 生命周期事件监听器。新增 Listener 不修改 Runtime。 */
public interface RuntimeListener {
    default void onEvent(RuntimeEvent e) {}
}
