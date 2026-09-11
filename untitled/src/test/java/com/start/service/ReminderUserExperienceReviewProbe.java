package com.start.service;

import com.start.agent.ReminderTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Desired user behavior; run explicitly with -Dtest=ReminderUserExperienceReviewProbe. */
class ReminderUserExperienceReviewProbe {
    private final ReminderService service = ReminderService.getInstance();

    @AfterEach
    void cleanUp() {
        service.setEnabled(false);
        service.setEnabled(true);
    }

    @Test
    void simpleDurationRemainsSupported() {
        assertEquals(1800, ReminderService.parseDelaySeconds("30\u5206\u949f"));
    }

    @Test
    void compoundDurationMeansNinetyMinutes() {
        assertEquals(5400, ReminderService.parseDelaySeconds("1\u5c0f\u65f630\u5206\u949f"));
    }

    @Test
    void disabledServiceMustNotConfirmCreation() {
        service.setEnabled(false);
        String result = new ReminderTool().execute(Map.of(
                "delay", "600", "message", "review", "user_id", "12345"));
        assertFalse(result.startsWith("\u5df2\u8bbe\u7f6e"), result);
    }

    @Test
    void remindersInTwoGroupsMustCoexist() throws Exception {
        service.remindAtGroup(10001, 12345, "first", LocalDateTime.now().plusDays(1));
        service.remindAtGroup(10002, 12345, "second", LocalDateTime.now().plusDays(1));
        assertEquals(2, activeTasks().size());
    }

    @Test
    void unrelatedPrivateChatMustNotCancelGroupReminder() throws Exception {
        service.remindAtGroup(10001, 12345, "first", LocalDateTime.now().plusDays(1));
        service.onPrivateMessageReceived(12345);
        assertEquals(1, activeTasks().size());
    }

    private Map<?, ?> activeTasks() throws Exception {
        Field field = ReminderService.class.getDeclaredField("activeTasks");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(service);
    }
}
