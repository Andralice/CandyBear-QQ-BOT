package com.start.agent;

import com.start.memory.MemoryEvidence;
import com.start.model.LongTermMemory;
import com.start.model.ProfileFactSummary;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.ProfileFactRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual SQL and tool calls against an isolated in-memory database; no application configuration. */
class GroundedMemoryFlowTest {
    JdbcDataSource ds;
    LongTermMemoryRepository repo;
    final LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

    @BeforeEach void database() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        repo = new LongTermMemoryRepository(ds);
        sql("CREATE TABLE messages(id BIGINT PRIMARY KEY,user_id VARCHAR(50),group_id VARCHAR(50),"
                + "raw_content TEXT,created_at TIMESTAMP,is_private BOOLEAN DEFAULT FALSE,is_robot_reply BOOLEAN DEFAULT FALSE,"
                + "sender_type VARCHAR(16) DEFAULT 'USER',availability_status VARCHAR(32) DEFAULT 'AVAILABLE',"
                + "retracted_at TIMESTAMP,redacted_at TIMESTAMP)");
        sql("CREATE TABLE long_term_memories(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id VARCHAR(50),group_id VARCHAR(50),"
                + "source_message_id BIGINT,content TEXT,memory_type VARCHAR(20),keywords TEXT,importance INT,trigger_at TIMESTAMP,"
                + "expires_at TIMESTAMP,source VARCHAR(20) DEFAULT 'SELF_REPORTED',status VARCHAR(20) DEFAULT 'ACTIVE',"
                + "confidence DOUBLE DEFAULT 1,last_seen_at TIMESTAMP,last_confirmed_at TIMESTAMP,last_used_at TIMESTAMP,"
                + "last_recalled TIMESTAMP,recall_count INT DEFAULT 0,triggered BOOLEAN DEFAULT FALSE,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }

    void sql(String statement, Object... args) throws Exception {
        try (var c = ds.getConnection(); var ps = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.execute();
        }
    }
    void message(long id, String user, String group, String text) throws Exception {
        sql("INSERT INTO messages(id,user_id,group_id,raw_content,created_at,is_private) VALUES(?,?,?,?,?,?)",
                id,user,group,text,now.minusHours(1).plusSeconds(id),group == null);
    }
    LongTermMemory cue(long id, String text) throws Exception {
        LongTermMemory m = new LongTermMemory();
        m.setUserId("u1"); m.setGroupId("g1"); m.setSourceMessageId(id);
        m.setContent(text); m.setKeywords("出行,车站"); m.setImportance(3);
        repo.insertGrounded(m, now);
        return m;
    }

    @Test void sourceMetadataSurvivesSqlAndReplayDoesNotConfirmIt() throws Exception {
        message(1,"u1","g1","周六去南站");
        LongTermMemory m = cue(1,"周六去南站");
        LongTermMemory retry = cue(1,"周六去南站");
        assertEquals(m.getId(), retry.getId());
        var saved = repo.search("u1","g1",null,10).get(0);
        assertEquals(1L,saved.getSourceMessageId());
        assertEquals("USER_MESSAGE",saved.getSource());
        assertEquals("UNCERTAIN",saved.getStatus());
        assertEquals(0,saved.getConfidence());
        assertNull(saved.getLastConfirmedAt());
        assertEquals(now.minusHours(1).plusSeconds(1).withNano(0),saved.getLastSeenAt().withNano(0));
    }

    @Test void similarCorrectionIsSavedAndRetrievedWithOldKeyword() throws Exception {
        message(1,"u1","g1","周六去南站");
        cue(1,"周六去南站");
        message(2,"u1","g1","改成北站，日期不变");
        cue(2,"改成北站，日期不变");
        assertEquals(2,repo.search("u1","g1",null,10).size());
        String answer = new RecallMemoryTool(repo,"u1","g1").execute(Map.of("user_id","u1","group_id","g1","keyword","南站"));
        assertTrue(answer.contains("周六去南站"));
        assertTrue(answer.contains("改成北站，日期不变"));
        assertFalse(answer.contains("CONFIRMED"));
    }

    @Test void provenanceRejectsWrongPersonScopeBotWithdrawalAndFuture() throws Exception {
        message(1,"u2","g1","他人的消息");
        message(2,"u1","g2","其他群");
        message(3,"u1",null,"私聊");
        message(4,"u1","g1","助手生成"); sql("UPDATE messages SET is_robot_reply=TRUE WHERE id=4");
        message(5,"u1","g1","撤回"); sql("UPDATE messages SET retracted_at=CURRENT_TIMESTAMP WHERE id=5");
        message(6,"u1","g1","已过期"); sql("UPDATE messages SET availability_status='EXPIRED' WHERE id=6");
        message(7,"u1","g1","未来"); sql("UPDATE messages SET created_at=? WHERE id=7",now.plusHours(1));
        message(8,"u1","g1",null);
        message(9,"u1","g1","脱敏"); sql("UPDATE messages SET redacted_at=CURRENT_TIMESTAMP WHERE id=9");
        for (int id=1;id<=9;id++) {
            final int source = id;
            assertThrows(IllegalArgumentException.class, () -> cue(source,"不应保存"), "source="+id);
        }
        assertTrue(repo.search("u1","g1",null,10).isEmpty());
    }

    @Test void toolCannotForgeOwnerAndNeedsActualSource() throws Exception {
        message(1,"u1","g1","我的画纸已经收到");
        RememberFactTool tool = new RememberFactTool(repo,"u1","g1");
        assertTrue(tool.execute(Map.of("content","画纸收到")).contains("source_message_id"));
        assertTrue(tool.execute(Map.of("content","画纸收到","source_message_id","1", "user_id","u2","group_id","g2")).startsWith("已记住"));
        assertEquals(1,repo.search("u1","g1",null,10).size());
        assertTrue(repo.search("u2","g2",null,10).isEmpty());
    }

    @Test void temporaryPreferenceDoesNotOverwriteGeneralPreference() throws Exception {
        message(1,"u1","g1","平时喜欢听歌"); cue(1,"平时喜欢听歌");
        message(2,"u1","g1","今天想安静，先不听了"); cue(2,"今天想安静，先不听了");
        String rendered = MemoryEvidence.render(repo,repo.search("u1","g1",null,10),now);
        assertTrue(rendered.contains("平时喜欢听歌"));
        assertTrue(rendered.contains("今天想安静，先不听了"));
        assertEquals(2,repo.search("u1","g1",null,10).size());
        assertFalse(rendered.contains("置信度"));
    }

    @Test void withdrawalAfterSavingRemovesCueAndRawTextFromRead() throws Exception {
        message(1,"u1","g1","WITHDRAWN_CANARY");
        LongTermMemory m = cue(1,"SAVED_SUMMARY_CANARY");
        sql("UPDATE messages SET retracted_at=CURRENT_TIMESTAMP WHERE id=1");
        String rendered = MemoryEvidence.render(repo,List.of(m),now);
        assertFalse(rendered.contains("WITHDRAWN_CANARY"));
        assertFalse(rendered.contains("SAVED_SUMMARY_CANARY"));
    }

    @Test void laterContextFiltersOtherPeopleGroupsPrivateAndUnavailableSources() throws Exception {
        message(1,"u1","g1","订单已接单"); LongTermMemory m = cue(1,"订单已接单");
        message(2,"u1","g1","刚收到发货通知");
        message(3,"u2","g1","OTHER_PERSON_CANARY");
        message(4,"u1","g2","OTHER_GROUP_CANARY");
        message(5,"u1",null,"PRIVATE_CANARY");
        message(6,"u1","g1","WITHDRAWN_CANARY"); sql("UPDATE messages SET availability_status='RETRACTED' WHERE id=6");
        String rendered = MemoryEvidence.render(repo,List.of(m),now);
        assertTrue(rendered.contains("刚收到发货通知"));
        assertFalse(rendered.contains("CANARY"));
    }

    @Test void memoryEvidenceMarksHistoricalStateAsNonRealtime() throws Exception {
        message(1,"u1","g1","订单已接单");
        String rendered = MemoryEvidence.render(repo,List.of(cue(1,"订单已接单")),now);
        assertTrue(rendered.contains("最后已知状态不等于实时状态"));
        assertTrue(rendered.contains("订单已接单"));
    }

    @Test void messageTimeIsExplicitAndExactEventTimeRemainsInOriginalText() throws Exception {
        message(1,"u1","g1","画纸已经收到");
        message(2,"u1","g1","杯子是10:15收到的");
        String rendered = MemoryEvidence.render(repo,List.of(cue(1,"画纸收到"),cue(2,"杯子收到")),now);
        assertTrue(rendered.contains("message_time"));
        assertTrue(rendered.contains("杯子是10:15收到的"));
        assertTrue(rendered.contains("event_time"));
        assertFalse(rendered.contains("10:15收到的画纸"));
    }

    @Test void dateSearchUsesSourceDateRatherThanLaterStorageDate() throws Exception {
        message(1,"u1","g1","昨天已收到画纸");
        sql("UPDATE messages SET created_at=? WHERE id=1",now.minusDays(2));
        cue(1,"昨天已收到画纸");
        String date=now.minusDays(2).toLocalDate().toString();
        assertEquals(1,repo.search("u1","g1","画纸",5,date,date).size());
        assertEquals(1,repo.searchByGroup("g1","画纸",5,date,date).size());
    }

    @Test void outputBudgetOmitsWholeRecordsRatherThanCuttingOffQualifiers() throws Exception {
        message(1,"u1","g1","长".repeat(16000)+"但只是今天");
        String result=MemoryEvidence.render(repo,List.of(cue(1,"长期喜欢安静")),now);
        var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.substring(result.indexOf('\n')+1));
        assertTrue(result.length()<16000);
        assertEquals(1,payload.path("omitted_records").asInt());
        assertFalse(payload.path("evidence_complete").asBoolean());
        assertFalse(result.contains("长期喜欢安静"));
    }

    @Test void privateRecallAndCrossGroupRequestsFailBeforeReturningContent() throws Exception {
        RecallMemoryTool privateTool = new RecallMemoryTool(repo,"u1",null);
        assertTrue(privateTool.execute(Map.of("user_id","u2")).contains("只能查询自己的"));
        assertTrue(new RecallMemoryTool(repo,"u1","g1").execute(Map.of("user_id","u1","group_id","g2")).contains("拒绝跨群"));
        message(1,"u1",null,"我的私聊原话");
        var m = new LongTermMemory(); m.setUserId("u1");m.setSourceMessageId(1L);m.setContent("私聊线索");
        repo.insertGrounded(m,now);
        assertTrue(new RecallMemoryTool(repo,"u1",null).execute(Map.of("user_id","u1")).contains("我的私聊原话"));
    }

    @Test void generatedPersonaSourceIsPersistedInsteadOfDefaultingToSelfReport() throws Exception {
        var m = new LongTermMemory();m.setUserId("bot");m.setContent("喜欢薄荷绿");m.setSource("AUTO_FORMED");m.setConfidence(.4);
        repo.insert(m);
        var saved = repo.search("bot",null,null,10).get(0);
        assertEquals("AUTO_FORMED",saved.getSource());assertEquals(.4,saved.getConfidence());
        assertTrue(MemoryEvidence.render(repo,List.of(saved),now).contains("BOT_FICTION_NOT_USER_TESTIMONY"));
    }

    @Test void profileUsesOriginalEvidenceAndHidesInaccessibleOrWithdrawnSources() throws Exception {
        message(1,"u1","g1","只是今天想听歌");
        message(2,"u1","g2","OTHER_GROUP_CANARY");
        ProfileFactRepository facts = new ProfileFactRepository(ds) {
            @Override public List<Map<String,Object>> findEvidenceForFact(long id,int limit) {
                return List.of(Map.of("message_id",id,"evidence_role","SUPPORT","availability_snapshot","AVAILABLE"));
            }
        };
        var summaries = List.of(new ProfileFactSummary(1,"preference","SELF_REPORTED","今天听歌","TEMPORARY",.9,"PERSONAL",now,null),
                new ProfileFactSummary(2,"preference","SELF_REPORTED","HIDDEN_CUE_CANARY","STABLE",.9,"PERSONAL",now,null));
        String rendered = MemoryEvidence.renderProfile(repo,facts,summaries,"u1","g1");
        assertTrue(rendered.contains("只是今天想听歌")); assertFalse(rendered.contains("CANARY"));
        sql("UPDATE messages SET retracted_at=CURRENT_TIMESTAMP WHERE id=1");
        assertFalse(MemoryEvidence.renderProfile(repo,facts,summaries,"u1","g1").contains("今天听歌"));
    }

    @Test void concurrentRetriesRemainOneObservation() throws Exception {
        message(1,"u1","g1","周六去南站");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var jobs = pool.invokeAll(List.<Callable<Long>>of(() -> cue(1,"周六去南站").getId(), () -> cue(1,"周六去南站").getId()));
            assertEquals(jobs.get(0).get(),jobs.get(1).get());
            assertEquals(1,repo.search("u1","g1",null,10).size());
        } finally { pool.shutdownNow(); }
    }

    @Test void scheduleKeepsRequestSourceSeparateFromTriggerAndDelivery() throws Exception {
        message(1,"u1","g1","明天提醒我取画纸");
        String result = new ScheduleEventTool(repo,"u1","g1").execute(Map.of("source_message_id","1", "content","取画纸",
                "trigger_time",now.plusDays(1).toLocalDate().toString()));
        assertTrue(result.startsWith("已保存"),result);
        var saved = repo.search("u1","g1",null,10).get(0);
        assertEquals("SCHEDULE_REQUEST",saved.getSource()); assertEquals(1L,saved.getSourceMessageId());
        assertEquals(9,saved.getTriggerAt().getHour()); assertFalse(saved.isTriggered());
        assertTrue(result.contains("尚未触发或送达"));
    }

    void profileTables() throws Exception {
        sql("CREATE TABLE profile_fact_candidates(id BIGINT PRIMARY KEY,subject_user_id VARCHAR,source_group_id VARCHAR,"
                + "kind VARCHAR,epistemic_type VARCHAR,claim_key VARCHAR,claim VARCHAR,polarity VARCHAR,status VARCHAR,"
                + "stability VARCHAR,confidence DOUBLE,scope VARCHAR,first_observed_at TIMESTAMP,last_observed_at TIMESTAMP,"
                + "valid_from TIMESTAMP,valid_until TIMESTAMP,updated_at TIMESTAMP)");
        sql("CREATE TABLE profile_fact_evidence(candidate_id BIGINT,fact_id BIGINT,message_id BIGINT,event_id BIGINT,"
                + "evidence_role VARCHAR,availability_snapshot VARCHAR)");
        sql("CREATE TABLE profile_facts(id BIGINT AUTO_INCREMENT PRIMARY KEY,subject_user_id VARCHAR,kind VARCHAR,"
                + "epistemic_type VARCHAR,claim_key VARCHAR,claim VARCHAR,polarity VARCHAR,status VARCHAR,stability VARCHAR,"
                + "confidence DOUBLE,scope VARCHAR,first_observed_at TIMESTAMP,last_observed_at TIMESTAMP,valid_from TIMESTAMP,"
                + "valid_until TIMESTAMP,source_candidate_id BIGINT,confirmed_at TIMESTAMP,review_state VARCHAR,version INT,"
                + "superseded_by_fact_id BIGINT)");
        sql("CREATE TABLE profile_fact_conflicts(existing_fact_id BIGINT,incoming_candidate_id BIGINT,conflict_type VARCHAR,"
                + "status VARCHAR,resolution VARCHAR,resolved_by VARCHAR,resolved_at TIMESTAMP)");
        sql("CREATE TABLE profile_fact_changes(entity_type VARCHAR,entity_id BIGINT,from_status VARCHAR,to_status VARCHAR,"
                + "old_snapshot VARCHAR,new_snapshot VARCHAR,reason_code VARCHAR,actor_type VARCHAR,actor_id VARCHAR,task_id BIGINT)");
    }
    void profileCandidate(String scope, String polarity, String claim, LocalDateTime until) throws Exception {
        sql("INSERT INTO profile_fact_candidates(id,subject_user_id,source_group_id,kind,epistemic_type,claim_key,claim,"
                + "polarity,status,confidence,scope,first_observed_at,last_observed_at,valid_from,valid_until) "
                + "VALUES(1,'u1',?,'preference','SELF_REPORTED','music',?,?,'CANDIDATE',.9,?,?,?,?,?)",
                scope.equals("PRIVATE_ONLY") ? null : "g1",claim,polarity,scope,now.minusDays(3),now,now,until);
    }
    void oldStableProfile() throws Exception {
        sql("INSERT INTO profile_facts(id,subject_user_id,claim_key,claim,polarity,status,stability,scope,confidence,last_observed_at) "
                + "VALUES(10,'u1','music','平时喜欢听歌','ASSERT','ACTIVE','STABLE','PERSONAL',.9,?)",now.minusDays(3));
    }
    String profileValue(long id, String column) throws Exception {
        if (!Set.of("status","stability").contains(column)) throw new IllegalArgumentException();
        try (var c=ds.getConnection();var ps=c.prepareStatement("SELECT "+column+" FROM profile_facts WHERE id=?")) {
            ps.setLong(1,id);try (var rs=ps.executeQuery()) { assertTrue(rs.next());return rs.getString(1); }
        }
    }
    void supportingMessage(long id, String user, String group) throws Exception {
        message(id,user,group,"测试中的用户原话");
        sql("INSERT INTO profile_fact_evidence(candidate_id,message_id,event_id,evidence_role,availability_snapshot) VALUES(1,?,?,'SUPPORT','AVAILABLE')",id,id);
    }

    @Test void selfReportedLabelWithoutEvidenceCannotPromote() throws Exception {
        profileTables();profileCandidate("PERSONAL","ASSERT","喜欢听歌",null);
        assertEquals(0,new ProfileFactRepository(ds).promoteCandidate(1));
    }
    @Test void anotherPersonsEvidenceCannotPromoteProfile() throws Exception {
        profileTables();profileCandidate("PERSONAL","ASSERT","喜欢听歌",null);supportingMessage(1,"u2","g1");
        assertEquals(0,new ProfileFactRepository(ds).promoteCandidate(1));
    }
    @Test void boundedDenialKeepsStandingPreferenceActive() throws Exception {
        profileTables();oldStableProfile();profileCandidate("PERSONAL","DENY","今天不想听歌",now.plusHours(8));
        supportingMessage(1,"u1","g1");
        long id = new ProfileFactRepository(ds).promoteCandidate(1);
        assertTrue(id>0);assertEquals("TEMPORARY",profileValue(id,"stability"));
        assertEquals("ACTIVE",profileValue(10,"status"));
    }
    @Test void boundedRepeatedEvidenceCannotBecomeStable() throws Exception {
        profileTables();profileCandidate("PERSONAL","ASSERT","这几天想听歌",now.plusHours(8));
        for(int id=1;id<=3;id++) {supportingMessage(id,"u1","g1");sql("UPDATE messages SET created_at=? WHERE id=?",now.minusDays(id),id);}
        long id=new ProfileFactRepository(ds).promoteCandidate(1);
        assertTrue(id>0);assertEquals("TEMPORARY",profileValue(id,"stability"));
    }
    @Test void sameClaimWithTemporaryScopeDoesNotShortenStableFact() throws Exception {
        profileTables();oldStableProfile();profileCandidate("PERSONAL","ASSERT","平时喜欢听歌",now.plusHours(8));
        supportingMessage(1,"u1","g1");
        long id=new ProfileFactRepository(ds).promoteCandidate(1);
        assertNotEquals(10,id);assertEquals("STABLE",profileValue(10,"stability"));
        assertEquals("TEMPORARY",profileValue(id,"stability"));
    }
    @Test void privateDenialDoesNotMutatePublicProfile() throws Exception {
        profileTables();oldStableProfile();profileCandidate("PRIVATE_ONLY","DENY","不喜欢听歌",null);
        supportingMessage(1,"u1",null);
        assertTrue(new ProfileFactRepository(ds).promoteCandidate(1)>0);
        assertEquals("ACTIVE",profileValue(10,"status"));
    }
    @Test void unboundedExplicitDenialStillSupersedesWithinSameScope() throws Exception {
        profileTables();oldStableProfile();profileCandidate("PERSONAL","DENY","以后不喜欢听歌",null);
        supportingMessage(1,"u1","g1");
        assertTrue(new ProfileFactRepository(ds).promoteCandidate(1)>0);
        assertEquals("SUPERSEDED",profileValue(10,"status"));
    }
}
