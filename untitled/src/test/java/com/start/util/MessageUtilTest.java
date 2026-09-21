package com.start.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageUtilTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void summarizesAllAddressingRelevantMessageSegments() throws Exception {
        var message = mapper.readTree("""
                [
                  {"type":"reply","data":{"id":"900"}},
                  {"type":"at","data":{"qq":"356289140"}},
                  {"type":"image","data":{"file":"a.jpg"}},
                  {"type":"face","data":{"id":"14"}},
                  {"type":"file","data":{"file":"说明.pdf"}},
                  {"type":"text","data":{"text":"看这个"}}
                ]
                """);

        String summary = MessageUtil.summarizeSegments(message);

        assertTrue(summary.contains("回复消息900"));
        assertTrue(summary.contains("@用户356289140"));
        assertTrue(summary.contains("图片"));
        assertTrue(summary.contains("表情"));
        assertTrue(summary.contains("文件「说明.pdf」"));
        assertTrue(summary.contains("文字"));
    }

    @Test
    void emptyImageMessageStillHasAnEvidenceType() throws Exception {
        var message = mapper.readTree("[{\"type\":\"image\",\"data\":{\"file\":\"a.jpg\"}}]");

        assertEquals("图片", MessageUtil.summarizeSegments(message));
    }
}
