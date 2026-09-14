package com.start.service;

import com.start.model.AuditReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditReportBuilderTest {

    @Test
    void parsesSnakeCaseReportAndKeepsAllImportantFields() {
        String output = "报告如下：\n"
                + "{\n"
                + "  \"severity\": \"严重\",\n"
                + "  \"need_fix\": true,\n"
                + "  \"issue\": \"messages content write failed\",\n"
                + "  \"root_cause\": \"legacy column type was too small\",\n"
                + "  \"evidence\": \"same error repeated\",\n"
                + "  \"affected_files\": [\"MessageRepository.java\", \"messages.content\"],\n"
                + "  \"suggested_fix\": \"ALTER TABLE messages MODIFY COLUMN content MEDIUMTEXT NOT NULL\",\n"
                + "  \"note\": \"verify the production schema first\"\n"
                + "}";

        AuditReport report = AuditReportBuilder.parse(output);

        assertNotNull(report);
        assertEquals("严重", report.getSeverity());
        assertEquals("messages content write failed", report.getSummary());
        assertEquals("legacy column type was too small", report.getRootCause());
        assertEquals("same error repeated", report.getEvidence());
        assertTrue(report.getLocation().contains("MessageRepository.java"));
        assertEquals(1, report.getSuggestions().size());
        assertTrue(report.isNeedsFix());
        String rendered = AuditReportBuilder.render(report);
        assertTrue(rendered.contains("根因：legacy column type was too small"));
        assertTrue(rendered.contains("证据：same error repeated"));
        assertTrue(rendered.contains("备注：verify the production schema first"));
    }

    @Test
    void parsesMarkdownEscapedSnakeCaseKey() {
        AuditReport report = AuditReportBuilder.parse(
                "{\"severity\":\"严重\",\"need\\\\_fix\":true,\"issue\":\"x\"}");

        assertNotNull(report);
        assertTrue(report.isNeedsFix());
        assertEquals("x", report.getSummary());
    }

    @Test
    void rejectsUnknownJsonInsteadOfCallingItStructured() {
        assertNull(AuditReportBuilder.parse("{\"answer\":\"not a report\"}"));
    }
}
