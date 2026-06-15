package com.start.agent;

import com.start.service.LinkPreviewService;

import java.util.List;
import java.util.Map;

/**
 * 链接预览工具 —— AI 可以主动获取链接的内容摘要
 */
public class LinkPreviewTool implements Tool {

    private final LinkPreviewService previewService;

    public LinkPreviewTool() {
        this.previewService = new LinkPreviewService();
    }

    @Override
    public String getName() { return "fetch_link_preview"; }

    @Override
    public String getDescription() {
        return "获取链接的网页信息（标题、描述等）。当用户问『这个链接是什么』『帮我看看这个链接』时调用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "要查看的完整URL")
                ),
                "required", List.of("url"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) return "缺少 URL";
        return previewService.fetchPreview(url);
    }
}
