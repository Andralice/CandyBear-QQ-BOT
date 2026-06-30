package com.start.service;

import java.util.*;

/** 规则集：结构化存储所有 Prompt Rule 段，支持按 category 筛选渲染 */
public class RuleSet {
    private final List<Section> sections = new ArrayList<>();

    public RuleSet add(Section section) {
        sections.add(section);
        return this;
    }

    public RuleSet add(String name, RuleCategory category, String text) {
        sections.add(new Section(name, category, text));
        return this;
    }

    /** 按指定 categories 筛选并渲染为 prompt 文本 */
    public String render(Set<RuleCategory> includeCategories) {
        StringBuilder sb = new StringBuilder();
        for (Section s : sections) {
            if (includeCategories.contains(s.category)) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(s.text);
            }
        }
        return sb.toString();
    }

    /** 渲染全部规则 */
    public String renderAll() {
        return render(EnumSet.allOf(RuleCategory.class));
    }

    public List<Section> sections() { return Collections.unmodifiableList(sections); }

    public record Section(String name, RuleCategory category, String text) {}
}
