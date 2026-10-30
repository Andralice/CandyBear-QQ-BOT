package com.start.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    @Test
    void bingResultsKeepTheSourceUrlAndDecodeHtmlAmpersands() {
        String html = """
                <li class="b_algo">
                  <h2><a href="https://example.com/guide?a=1&amp;b=2">新梗解释</a></h2>
                  <p>说明这个词近期在网络上的用法。</p>
                </li>
                """;

        List<String> results = new WebSearchTool().parseBing(html);

        assertEquals(1, results.size());
        assertTrue(results.get(0).contains("新梗解释 - 说明这个词近期在网络上的用法。"));
        assertTrue(results.get(0).contains("https://example.com/guide?a=1&b=2"));
    }
}
