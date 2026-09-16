package com.start;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MainPokeNoticeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BOT_QQ = 90001L;

    @Test
    void groupPokeBuildsReplyForTheActor() throws Exception {
        JsonNode event = MAPPER.readTree("""
                {"post_type":"notice","notice_type":"notify","sub_type":"poke",
                 "self_id":90001,"user_id":10001,"target_id":90001,"group_id":777}
                """);

        ObjectNode params = Main.buildPokeReplyParams(event, BOT_QQ);

        assertNotNull(params);
        assertEquals(10001L, params.path("user_id").asLong());
        assertEquals(777L, params.path("group_id").asLong());
    }

    @Test
    void privatePokeDoesNotInventGroupId() throws Exception {
        JsonNode event = MAPPER.readTree("""
                {"post_type":"notice","notice_type":"notify","sub_type":"poke",
                 "self_id":90001,"user_id":10001,"target_id":90001}
                """);

        ObjectNode params = Main.buildPokeReplyParams(event, BOT_QQ);

        assertNotNull(params);
        assertEquals(10001L, params.path("user_id").asLong());
        assertNull(params.get("group_id"));
    }

    @Test
    void ignoresOtherTargetsSelfPokesAndNonPokeNotices() throws Exception {
        JsonNode otherTarget = MAPPER.readTree("""
                {"post_type":"notice","notice_type":"notify","sub_type":"poke",
                 "self_id":90001,"user_id":10001,"target_id":10002,"group_id":777}
                """);
        JsonNode selfPoke = MAPPER.readTree("""
                {"post_type":"notice","notice_type":"notify","sub_type":"poke",
                 "self_id":90001,"user_id":90001,"target_id":90001,"group_id":777}
                """);
        JsonNode otherNotice = MAPPER.readTree("""
                {"post_type":"notice","notice_type":"group_recall",
                 "self_id":90001,"user_id":10001,"target_id":90001,"group_id":777}
                """);

        assertNull(Main.buildPokeReplyParams(otherTarget, BOT_QQ));
        assertNull(Main.buildPokeReplyParams(selfPoke, BOT_QQ));
        assertNull(Main.buildPokeReplyParams(otherNotice, BOT_QQ));
    }
}
