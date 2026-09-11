package com.start.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.model.MessagePart;
import org.junit.jupiter.api.Test;

import java.util.List;

class MessageIngestionPolicyTest {

    @Test
    void sourceEventKeyScopesGroupMessagesAndKeepsRedeliveryStable() {
        String first = MessageService.sourceEventKey("group", "10001", "20002", "30003");
        String replay = MessageService.sourceEventKey("group", "10001", "20002", "30003");
        String anotherGroup = MessageService.sourceEventKey("group", "10002", "20002", "30003");

        assertEquals("group:10001:20002:30003", first);
        assertEquals(first, replay);
        assertEquals("group:10002:20002:30003", anotherGroup);
    }

    @Test
    void sourceEventKeyDoesNotInventIdentityForLegacyEvents() {
        assertNull(MessageService.sourceEventKey("private", null, "20002", ""));
    }

    @Test
    void oneBotSegmentsRemainOrderedAndUnavailableMediaIsExplicit() throws Exception {
        var segments = new ObjectMapper().readTree("""
                [
                  {"type":"text","data":{"text":"你好"}},
                  {"type":"at","data":{"qq":"3524398813"}},
                  {"type":"reply","data":{"id":"9988"}},
                  {"type":"image","data":{"url":"https://example.test/a.png","file_size":"12"}},
                  {"type":"record","data":{}}
                ]
                """);

        List<MessagePart> parts = MessageService.toEvidenceParts(segments);

        assertEquals(5, parts.size());
        assertEquals("你好", parts.get(0).textValue());
        assertEquals("3524398813", parts.get(1).targetId());
        assertEquals("9988", parts.get(2).targetId());
        assertEquals("PENDING", parts.get(3).availabilityStatus());
        assertEquals(12L, parts.get(3).byteSize());
        assertEquals("UNAVAILABLE_REFERENCE", parts.get(4).availabilityStatus());
    }
}
