package com.start.runtime.conversation;

import java.util.List;
import java.util.Map;

/** 群消息 DTO，解耦 Runtime 和 OneBot JsonNode。 */
public record GroupMessage(
        long groupId,
        long userId,
        String nickname,
        String senderNick,
        String rawMessage,
        String plainText,
        List<Long> ats,
        List<Map<String, String>> imageInfos,
        List<Map<String, String>> fileInfos,
        List<String> linksToFetch,
        Long replyToMessageId) {

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private long groupId;
        private long userId;
        private String nickname = "";
        private String senderNick = "";
        private String rawMessage = "";
        private String plainText = "";
        private List<Long> ats = List.of();
        private List<Map<String, String>> imageInfos = List.of();
        private List<Map<String, String>> fileInfos = List.of();
        private List<String> linksToFetch = List.of();
        private Long replyToMessageId;

        public Builder groupId(long v) { groupId = v; return this; }
        public Builder userId(long v) { userId = v; return this; }
        public Builder nickname(String v) { nickname = v; return this; }
        public Builder senderNick(String v) { senderNick = v; return this; }
        public Builder rawMessage(String v) { rawMessage = v; return this; }
        public Builder plainText(String v) { plainText = v; return this; }
        public Builder ats(List<Long> v) { ats = v; return this; }
        public Builder imageInfos(List<Map<String, String>> v) { imageInfos = v; return this; }
        public Builder fileInfos(List<Map<String, String>> v) { fileInfos = v; return this; }
        public Builder linksToFetch(List<String> v) { linksToFetch = v; return this; }
        public Builder replyToMessageId(Long v) { replyToMessageId = v; return this; }
        public GroupMessage build() {
            return new GroupMessage(groupId, userId, nickname, senderNick,
                    rawMessage, plainText, ats, imageInfos, fileInfos, linksToFetch, replyToMessageId);
        }
    }
}
