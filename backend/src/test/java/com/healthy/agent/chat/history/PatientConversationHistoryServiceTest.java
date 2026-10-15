package com.healthy.agent.chat.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.common.AgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientConversationHistoryServiceTest {
    private static final String RAW = "11111111-1111-4111-8111-111111111111";
    private static final String SCOPED = "patient:8:" + RAW;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PatientConversationHistoryService service =
            new PatientConversationHistoryService(
                    redis, mapper, Clock.fixed(
                    Instant.parse("2026-10-10T02:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUpOperations() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForZSet()).thenReturn(zsets);
    }

    @Test
    void modelContextIsCappedAtTwentyAndReturnedOldestFirst() throws Exception {
        when(zsets.reverseRange(anyString(), eq(0L), eq(19L)))
                .thenReturn(new LinkedHashSet<>(List.of("m3", "m2", "m1")));
        when(hashes.get(anyString(), eq("m1"))).thenReturn(json(message(
                "m1", ConversationRole.USER, "第一条")));
        when(hashes.get(anyString(), eq("m2"))).thenReturn(json(message(
                "m2", ConversationRole.ASSISTANT, "第二条")));
        when(hashes.get(anyString(), eq("m3"))).thenReturn(json(message(
                "m3", ConversationRole.USER, "第三条")));

        List<Message> result = service.recentModelMessages(SCOPED, 100);

        assertThat(result).extracting(Message::getText)
                .containsExactly("第一条", "第二条", "第三条");
        verify(zsets).reverseRange(
                "healthy-agent:chat:conversation:" + SCOPED + ":messages", 0, 19);
    }

    @Test
    void summaryBatchContainsOnlyCompleteTurnsOutsideRetainedWindow() throws Exception {
        when(zsets.zCard(anyString())).thenReturn(22L);
        when(zsets.rangeWithScores(anyString(), eq(3L), eq(3L)))
                .thenReturn(Set.of(new DefaultTypedTuple<>("m4", 4D)));
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("m1", 1D));
        tuples.add(new DefaultTypedTuple<>("m2", 2D));
        tuples.add(new DefaultTypedTuple<>("m3", 3D));
        tuples.add(new DefaultTypedTuple<>("m4", 4D));
        when(zsets.rangeByScoreWithScores(
                anyString(), eq(1D), eq(4D), eq(0L), eq(20L))).thenReturn(tuples);
        when(hashes.get(anyString(), eq("m1"))).thenReturn(json(message(
                "m1", ConversationRole.USER, "第一轮问题")));
        when(hashes.get(anyString(), eq("m2"))).thenReturn(json(message(
                "m2", ConversationRole.ASSISTANT, "第一轮回答")));
        when(hashes.get(anyString(), eq("m3"))).thenReturn(json(message(
                "m3", ConversationRole.USER, "第二轮问题")));
        when(hashes.get(anyString(), eq("m4"))).thenReturn(json(message(
                "m4", ConversationRole.ASSISTANT, "第二轮回答")));

        ConversationSummaryBatch result = service.nextSummaryBatch(
                SCOPED, 0L, 18, 20);

        assertThat(result.throughSequence()).isEqualTo(4L);
        assertThat(result.messages()).extracting(ConversationMemoryMessage::content)
                .containsExactly("第一轮问题", "第一轮回答", "第二轮问题", "第二轮回答");
    }

    @Test
    void pageUsesStableSequenceCursorAndReturnsChronologicalMessages() throws Exception {
        Map<Object, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", "8");
        metadata.put("conversationId", RAW);
        when(hashes.entries(anyString())).thenReturn(metadata);
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("m4", 4D));
        tuples.add(new DefaultTypedTuple<>("m3", 3D));
        tuples.add(new DefaultTypedTuple<>("m2", 2D));
        when(zsets.reverseRangeByScoreWithScores(
                anyString(), eq(0D), eq(9D), eq(0L), eq(3L))).thenReturn(tuples);
        when(hashes.get(anyString(), eq("m3"))).thenReturn(json(message(
                "m3", ConversationRole.USER, "较早消息")));
        when(hashes.get(anyString(), eq("m4"))).thenReturn(json(message(
                "m4", ConversationRole.ASSISTANT, "较新消息")));

        ConversationMessagePage page = service.messages(8L, RAW, 10L, 2);

        assertThat(page.messages()).extracting(ConversationMessageView::sequence)
                .containsExactly(3L, 4L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextBeforeSequence()).isEqualTo(3L);
    }

    @Test
    void anotherUsersMetadataCannotBeRead() {
        when(hashes.entries(anyString())).thenReturn(Map.of(
                "userId", "9", "conversationId", RAW));

        assertThatThrownBy(() -> service.requireOwned(8L, RAW))
                .isInstanceOf(AgentException.class)
                .extracting("errorCode.code").isEqualTo(40430);
    }

    @Test
    void renameUpdatesOwnedMetadataAndConversationOrder() {
        Map<Object, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", "8");
        metadata.put("conversationId", RAW);
        metadata.put("title", "旧名称");
        metadata.put("createdAt", "2026-10-10T01:00:00Z");
        metadata.put("updatedAt", "2026-10-10T01:00:00Z");
        when(hashes.entries(anyString())).thenReturn(metadata, Map.of(
                "userId", "8",
                "conversationId", RAW,
                "title", "复诊安排",
                "createdAt", "2026-10-10T01:00:00Z",
                "updatedAt", "2026-10-10T02:00:00Z"));
        when(zsets.zCard(anyString())).thenReturn(6L);

        ConversationSummary renamed = service.rename(8L, RAW, "  复诊安排  ");

        assertThat(renamed.title()).isEqualTo("复诊安排");
        assertThat(renamed.messageCount()).isEqualTo(6);
        verify(hashes).put("healthy-agent:chat:conversation:" + SCOPED + ":meta",
                "title", "复诊安排");
        verify(hashes).put("healthy-agent:chat:conversation:" + SCOPED + ":meta",
                "customTitle", "1");
        verify(zsets).add("healthy-agent:chat:user:8:conversations", RAW,
                Instant.parse("2026-10-10T02:00:00Z").toEpochMilli());
    }

    @Test
    void rejectsBlankOrOverlongConversationTitle() {
        when(hashes.entries(anyString())).thenReturn(Map.of(
                "userId", "8", "conversationId", RAW));

        assertThatThrownBy(() -> service.rename(8L, RAW, " \n "))
                .isInstanceOf(AgentException.class)
                .extracting("errorCode.code").isEqualTo(40019);
        assertThatThrownBy(() -> service.rename(8L, RAW, "名".repeat(61)))
                .isInstanceOf(AgentException.class)
                .extracting("errorCode.code").isEqualTo(40019);
    }

    private StoredConversationMessage message(
            String id,
            ConversationRole role,
            String content
    ) {
        return new StoredConversationMessage(
                id, role, content, Instant.parse("2026-10-10T01:00:00Z"), null);
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }
}
