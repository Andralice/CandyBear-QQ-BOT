package com.start.service;

import com.start.config.BotConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cheap, deterministic evidence for a leading vocative such as "小鲸鱼，你看得到吗". */
public final class GroupAddressingEvidence {
    private static final Pattern LEADING_ADDRESS = Pattern.compile(
            "^\\s*([\\p{IsHan}\\p{L}\\d_]{1,20})\\s*(?:[，,:：、]|\\s+)\\s*"
                    + "(?=(?:你|您|这是|那是|(?:请)?(?:介绍|说说|讲讲)(?:一下|下)?(?:你|您)(?:自己)?))");

    private GroupAddressingEvidence() {}

    public static LeadingAddress detect(String message) {
        if (message == null || message.isBlank()) return LeadingAddress.NONE;
        Matcher matcher = LEADING_ADDRESS.matcher(message.trim());
        if (!matcher.find()) return LeadingAddress.NONE;

        String label = matcher.group(1).trim();
        String botName = BotConfig.getBotName();
        boolean bot = (botName != null && !botName.isBlank()
                && normalize(label).equals(normalize(botName)))
                || normalize(label).equals("糖果熊");
        return new LeadingAddress(label, bot);
    }

    public static boolean addressesOtherThanBot(String message) {
        LeadingAddress address = detect(message);
        return address.present() && !address.bot();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    public record LeadingAddress(String label, boolean bot) {
        private static final LeadingAddress NONE = new LeadingAddress("", false);

        public boolean present() {
            return !label.isBlank();
        }
    }
}
