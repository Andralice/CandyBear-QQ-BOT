package com.start.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动巡检报告：主 AI 排查完日志异常后输出的结构化结果。
 * 由 ErrorMonitorService 解析主 AI 输出得到，再交给 AuditReportBuilder 翻译为给归儿的可读消息。
 */
public class AuditReport {

    /** 严重程度：严重 / 一般 / 可忽略 */
    private String severity = "可忽略";

    /** 一句话结论（必填） */
    private String summary = "";

    /** 出问题的文件:方法（可空） */
    private String location = "";

    /** 异常类型（可空） */
    private String exceptionType = "";

    /** 根因（兼容 snake_case 巡检报告协议） */
    private String rootCause = "";

    /** 证据（兼容 snake_case 巡检报告协议） */
    private String evidence = "";

    /** 备注（兼容 snake_case 巡检报告协议） */
    private String note = "";

    /** 建议修复方案（可空，多条） */
    private List<String> suggestions = new ArrayList<>();

    /** 是否需要归儿介入处理（true=要修 / false=无需修） */
    private boolean needsFix = false;

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }

    public boolean isNeedsFix() { return needsFix; }
    public void setNeedsFix(boolean needsFix) { this.needsFix = needsFix; }
}
