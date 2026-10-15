package com.healthy.agent.chat.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.action.PatientActionResponse;
import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.chat.PatientChatResponse;
import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.state.PatientConversationIds;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PatientConversationHistoryService {
    public static final int MODEL_CONTEXT_MESSAGES = 20;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private static final String PREFIX = "healthy-agent:chat:";
    private static final String DEFAULT_TITLE = "新对话";
    private static final int MAX_TITLE_CHARACTERS = 36;
    private static final int MAX_CUSTOM_TITLE_CHARACTERS = 60;

    private static final DefaultRedisScript<Long> APPEND_EXCHANGE_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('HEXISTS', KEYS[7], ARGV[9]) == 1 then
                      return -1
                    end
                    local userSequence = redis.call('INCR', KEYS[3])
                    redis.call('ZADD', KEYS[1], userSequence, ARGV[1])
                    redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
                    local assistantSequence = redis.call('INCR', KEYS[3])
                    redis.call('ZADD', KEYS[1], assistantSequence, ARGV[3])
                    redis.call('HSET', KEYS[2], ARGV[3], ARGV[4])
                    if ARGV[8] ~= '' then
                      redis.call('HSET', KEYS[6], ARGV[8], ARGV[3])
                    end
                    redis.call('HSET', KEYS[7], ARGV[9], '1')
                    if redis.call('HLEN', KEYS[4]) == 0 then
                      redis.call('HSET', KEYS[4], 'userId', ARGV[10], 'conversationId', ARGV[7],
                        'title', ARGV[6], 'createdAt', ARGV[11])
                    elseif redis.call('ZCARD', KEYS[1]) == 2
                        and redis.call('HGET', KEYS[4], 'customTitle') ~= '1' then
                      redis.call('HSET', KEYS[4], 'title', ARGV[6])
                    end
                    redis.call('HSET', KEYS[4], 'updatedAt', ARGV[5])
                    redis.call('ZADD', KEYS[5], ARGV[12], ARGV[7])
                    return assistantSequence
                    """, Long.class);

    private static final DefaultRedisScript<Long> SAVE_SUMMARY_SCRIPT =
            new DefaultRedisScript<>("""
                    local current = redis.call('HGET', KEYS[1], 'summarizedThroughSequence')
                    if current == false then current = '0' end
                    if current ~= ARGV[1] then return 0 end
                    redis.call('HSET', KEYS[1],
                      'content', ARGV[2],
                      'summarizedThroughSequence', ARGV[3],
                      'updatedAt', ARGV[4])
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PatientConversationHistoryService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper
    ) {
        this(redis, objectMapper, Clock.systemUTC());
    }

    PatientConversationHistoryService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ConversationSummary create(long userId) {
        String rawConversationId = UUID.randomUUID().toString();
        String scoped = PatientConversationIds.scoped(userId, rawConversationId);
        Instant now = clock.instant();
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("userId", Long.toString(userId));
            metadata.put("conversationId", rawConversationId);
            metadata.put("title", DEFAULT_TITLE);
            metadata.put("customTitle", "0");
            metadata.put("createdAt", now.toString());
            metadata.put("updatedAt", now.toString());
            redis.opsForHash().putAll(metaKey(scoped), metadata);
            redis.opsForZSet().add(userConversationsKey(userId), rawConversationId,
                    score(now));
            return new ConversationSummary(
                    rawConversationId, DEFAULT_TITLE, now, now, 0);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public List<ConversationSummary> list(long userId) {
        try {
            Set<String> ids = redis.opsForZSet().reverseRange(
                    userConversationsKey(userId), 0, -1);
            if (ids == null || ids.isEmpty()) return List.of();
            List<ConversationSummary> summaries = new ArrayList<>(ids.size());
            for (String id : ids) {
                String scoped = PatientConversationIds.scoped(userId, id);
                Map<Object, Object> metadata = redis.opsForHash().entries(metaKey(scoped));
                if (!ownedBy(metadata, userId)) {
                    redis.opsForZSet().remove(userConversationsKey(userId), id);
                    continue;
                }
                summaries.add(summary(scoped, id, metadata));
            }
            return List.copyOf(summaries);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public ConversationSummary rename(
            long userId,
            String rawConversationId,
            String requestedTitle
    ) {
        String scoped = requireOwned(userId, rawConversationId);
        String title = normalizeCustomTitle(requestedTitle);
        Instant now = clock.instant();
        try {
            redis.opsForHash().put(metaKey(scoped), "title", title);
            redis.opsForHash().put(metaKey(scoped), "customTitle", "1");
            redis.opsForHash().put(metaKey(scoped), "updatedAt", now.toString());
            redis.opsForZSet().add(userConversationsKey(userId), rawConversationId, score(now));
            Map<Object, Object> metadata = redis.opsForHash().entries(metaKey(scoped));
            return summary(scoped, rawConversationId, metadata);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public String requireOwned(long userId, String rawConversationId) {
        String scoped = PatientConversationIds.scoped(userId, rawConversationId);
        try {
            Map<Object, Object> metadata = redis.opsForHash().entries(metaKey(scoped));
            if (!ownedBy(metadata, userId)) {
                throw new AgentException(AgentErrorCode.CONVERSATION_NOT_FOUND);
            }
            return scoped;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public ConversationMessagePage messages(
            long userId,
            String rawConversationId,
            Long beforeSequence,
            int requestedSize
    ) {
        String scoped = requireOwned(userId, rawConversationId);
        int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
        double maximum = beforeSequence == null
                ? Double.POSITIVE_INFINITY : Math.max(0, beforeSequence - 1);
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeByScoreWithScores(
                            messagesKey(scoped), 0, maximum, 0, size + 1L);
            if (tuples == null || tuples.isEmpty()) {
                return new ConversationMessagePage(List.of(), null, false);
            }
            List<ZSetOperations.TypedTuple<String>> newestFirst = new ArrayList<>(tuples);
            boolean hasMore = newestFirst.size() > size;
            if (hasMore) newestFirst.removeLast();
            Collections.reverse(newestFirst);

            List<ConversationMessageView> result = new ArrayList<>(newestFirst.size());
            for (ZSetOperations.TypedTuple<String> tuple : newestFirst) {
                StoredConversationMessage stored = readPayload(scoped, tuple.getValue());
                if (stored == null || tuple.getScore() == null) continue;
                result.add(toView(Math.round(tuple.getScore()), stored));
            }
            Long nextBefore = hasMore && !result.isEmpty()
                    ? result.getFirst().sequence() : null;
            return new ConversationMessagePage(result, nextBefore, hasMore);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public List<Message> recentModelMessages(String scopedConversationId, int maximum) {
        int limit = Math.max(0, Math.min(maximum, MODEL_CONTEXT_MESSAGES));
        if (limit == 0) return List.of();
        try {
            Set<String> ids = redis.opsForZSet().reverseRange(
                    messagesKey(scopedConversationId), 0, limit - 1L);
            if (ids == null || ids.isEmpty()) return List.of();
            List<String> newestFirst = new ArrayList<>(ids);
            Collections.reverse(newestFirst);
            List<Message> messages = new ArrayList<>(newestFirst.size());
            for (String id : newestFirst) {
                StoredConversationMessage stored = readPayload(scopedConversationId, id);
                if (stored == null || stored.content() == null || stored.content().isBlank()) {
                    continue;
                }
                messages.add(stored.role() == ConversationRole.USER
                        ? new UserMessage(stored.content())
                        : new AssistantMessage(stored.content()));
            }
            return List.copyOf(messages);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public ConversationMemorySummary memorySummary(String scopedConversationId) {
        try {
            Map<Object, Object> values = redis.opsForHash().entries(
                    summaryKey(scopedConversationId));
            if (values == null || values.isEmpty()) return ConversationMemorySummary.empty();
            String content = string(values.get("content"), "");
            long through = longValue(values.get("summarizedThroughSequence"), 0L);
            Instant updatedAt = nullableInstant(values.get("updatedAt"));
            return new ConversationMemorySummary(content, through, updatedAt);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * Returns the next complete user/assistant turns that are older than the retained raw window.
     * The caller merges this batch into the rolling summary and advances via compare-and-set.
     */
    public ConversationSummaryBatch nextSummaryBatch(
            String scopedConversationId,
            long afterSequence,
            int retainedRawMessages,
            int maximumBatchMessages
    ) {
        int retained = Math.max(0, retainedRawMessages);
        int batchSize = Math.max(2, maximumBatchMessages);
        if ((batchSize & 1) != 0) batchSize--;
        try {
            Long totalValue = redis.opsForZSet().zCard(messagesKey(scopedConversationId));
            long total = totalValue == null ? 0L : totalValue;
            long overflow = total - retained;
            if (overflow <= 0L) return ConversationSummaryBatch.empty(afterSequence);

            Set<ZSetOperations.TypedTuple<String>> cutoff = redis.opsForZSet()
                    .rangeWithScores(messagesKey(scopedConversationId), overflow - 1L, overflow - 1L);
            if (cutoff == null || cutoff.isEmpty()) {
                return ConversationSummaryBatch.empty(afterSequence);
            }
            ZSetOperations.TypedTuple<String> cutoffTuple = cutoff.iterator().next();
            if (cutoffTuple.getScore() == null) {
                return ConversationSummaryBatch.empty(afterSequence);
            }
            long targetSequence = Math.round(cutoffTuple.getScore());
            if (targetSequence <= afterSequence) {
                return ConversationSummaryBatch.empty(afterSequence);
            }

            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .rangeByScoreWithScores(
                            messagesKey(scopedConversationId),
                            afterSequence + 1D,
                            targetSequence,
                            0L,
                            batchSize);
            if (tuples == null || tuples.isEmpty()) {
                return ConversationSummaryBatch.empty(afterSequence);
            }

            List<ConversationMemoryMessage> messages = new ArrayList<>(tuples.size());
            int lastCompleteTurn = -1;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getScore() == null) continue;
                StoredConversationMessage stored = readPayload(
                        scopedConversationId, tuple.getValue());
                if (stored == null || stored.content() == null || stored.content().isBlank()) {
                    continue;
                }
                messages.add(new ConversationMemoryMessage(
                        Math.round(tuple.getScore()), stored.role(), stored.content()));
                if (stored.role() == ConversationRole.ASSISTANT) {
                    lastCompleteTurn = messages.size() - 1;
                }
            }
            if (lastCompleteTurn < 0) {
                return ConversationSummaryBatch.empty(afterSequence);
            }
            List<ConversationMemoryMessage> completeTurns = List.copyOf(
                    messages.subList(0, lastCompleteTurn + 1));
            return new ConversationSummaryBatch(
                    completeTurns, completeTurns.getLast().sequence());
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public boolean saveMemorySummary(
            String scopedConversationId,
            long expectedThroughSequence,
            ConversationMemorySummary summary
    ) {
        if (summary == null || !summary.present()
                || summary.summarizedThroughSequence() <= expectedThroughSequence) {
            return false;
        }
        try {
            Long updated = redis.execute(
                    SAVE_SUMMARY_SCRIPT,
                    List.of(summaryKey(scopedConversationId)),
                    Long.toString(expectedThroughSequence),
                    summary.content(),
                    Long.toString(summary.summarizedThroughSequence()),
                    summary.updatedAt() == null
                            ? clock.instant().toString() : summary.updatedAt().toString());
            return updated != null && updated == 1L;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public void appendExchange(
            long userId,
            String rawConversationId,
            String question,
            PatientChatResponse response
    ) {
        String scoped = requireOwned(userId, rawConversationId);
        PatientChatResponse persistedResponse = response.withoutTracePayload();
        Instant now = clock.instant();
        String userMessageId = UUID.randomUUID().toString();
        String assistantMessageId = UUID.randomUUID().toString();
        StoredConversationMessage userMessage = new StoredConversationMessage(
                userMessageId, ConversationRole.USER, question, now, null);
        StoredConversationMessage assistantMessage = new StoredConversationMessage(
                assistantMessageId, ConversationRole.ASSISTANT,
                persistedResponse.answer(), now, persistedResponse);
        String actionId = persistedResponse.pendingAction() == null
                ? "" : persistedResponse.pendingAction().actionId();
        String turnId = persistedResponse.turnId() == null
                ? UUID.randomUUID().toString() : persistedResponse.turnId();
        try {
            redis.execute(APPEND_EXCHANGE_SCRIPT, List.of(
                            messagesKey(scoped), payloadsKey(scoped), sequenceKey(scoped),
                            metaKey(scoped), userConversationsKey(userId), actionsKey(scoped),
                            turnsKey(scoped)),
                    userMessageId, json(userMessage),
                    assistantMessageId, json(assistantMessage),
                    now.toString(), titleFrom(question), rawConversationId, actionId,
                    turnId, Long.toString(userId), now.toString(), Long.toString(score(now)));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public void updateAction(
            long userId,
            String rawConversationId,
            PatientActionResponse actionResult
    ) {
        String scoped = requireOwned(userId, rawConversationId);
        if (actionResult == null || actionResult.actionId() == null) return;
        try {
            Object indexed = redis.opsForHash().get(
                    actionsKey(scoped), actionResult.actionId());
            if (indexed == null) return;
            String messageId = indexed.toString();
            StoredConversationMessage stored = readPayload(scoped, messageId);
            if (stored == null || stored.response() == null) return;

            PatientChatResponse original = stored.response();
            PendingActionView pending = original.pendingAction();
            if (pending != null && pending.actionId().equals(actionResult.actionId())) {
                pending = new PendingActionView(
                        pending.actionId(), pending.type(), actionResult.status(),
                        pending.preview(), pending.expiresAt());
            }
            PatientActionResponse safeResult = new PatientActionResponse(
                    actionResult.actionId(), actionResult.type(), actionResult.status(),
                    actionResult.message(), actionResult.preview(), actionResult.turnId(), null);
            PatientChatResponse updatedResponse = new PatientChatResponse(
                    original.question(), original.answer(), original.mode(), original.model(),
                    original.embeddingModel(), original.citations(), original.usage(),
                    original.tools(), pending, safeResult, original.turnId(), null);
            String updatedContent = original.answer() + "\n操作结果：" + actionResult.message();
            StoredConversationMessage updated = new StoredConversationMessage(
                    stored.messageId(), stored.role(), updatedContent,
                    stored.createdAt(), updatedResponse);
            redis.opsForHash().put(payloadsKey(scoped), messageId, json(updated));
            Instant now = clock.instant();
            redis.opsForHash().put(metaKey(scoped), "updatedAt", now.toString());
            redis.opsForZSet().add(userConversationsKey(userId), rawConversationId, score(now));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public void delete(long userId, String rawConversationId) {
        String scoped = requireOwned(userId, rawConversationId);
        try {
            redis.delete(List.of(
                    messagesKey(scoped), payloadsKey(scoped), sequenceKey(scoped),
                    metaKey(scoped), actionsKey(scoped), turnsKey(scoped), summaryKey(scoped)));
            redis.opsForZSet().remove(userConversationsKey(userId), rawConversationId);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private ConversationSummary summary(
            String scoped,
            String conversationId,
            Map<Object, Object> metadata
    ) {
        Instant createdAt = instant(metadata.get("createdAt"));
        Instant updatedAt = instant(metadata.get("updatedAt"));
        Long count = redis.opsForZSet().zCard(messagesKey(scoped));
        return new ConversationSummary(
                conversationId,
                string(metadata.get("title"), DEFAULT_TITLE),
                createdAt,
                updatedAt,
                count == null ? 0 : count);
    }

    private boolean ownedBy(Map<Object, Object> metadata, long userId) {
        return metadata != null
                && Long.toString(userId).equals(string(metadata.get("userId"), null));
    }

    private StoredConversationMessage readPayload(String scoped, String messageId) {
        if (messageId == null) return null;
        Object value = redis.opsForHash().get(payloadsKey(scoped), messageId);
        if (value == null) return null;
        try {
            return objectMapper.readValue(value.toString(), StoredConversationMessage.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private ConversationMessageView toView(
            long sequence,
            StoredConversationMessage stored
    ) {
        return new ConversationMessageView(
                sequence, stored.messageId(), stored.role(), stored.content(),
                stored.createdAt(), stored.response());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AgentException(AgentErrorCode.INTERNAL_ERROR);
        }
    }

    private String titleFrom(String question) {
        if (question == null || question.isBlank()) return DEFAULT_TITLE;
        String title = question.replace('\n', ' ').replace('\r', ' ').strip();
        return title.length() <= MAX_TITLE_CHARACTERS
                ? title : title.substring(0, MAX_TITLE_CHARACTERS) + "…";
    }

    private String normalizeCustomTitle(String requestedTitle) {
        if (requestedTitle == null) {
            throw new AgentException(AgentErrorCode.INVALID_CONVERSATION_TITLE);
        }
        String title = requestedTitle.replace('\n', ' ').replace('\r', ' ').strip();
        int characters = title.codePointCount(0, title.length());
        if (title.isEmpty() || characters > MAX_CUSTOM_TITLE_CHARACTERS) {
            throw new AgentException(AgentErrorCode.INVALID_CONVERSATION_TITLE);
        }
        return title;
    }

    private Instant instant(Object value) {
        try {
            return value == null ? clock.instant() : Instant.parse(value.toString());
        } catch (DateTimeParseException exception) {
            return clock.instant();
        }
    }

    private Instant nullableInstant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(value.toString());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String string(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private long score(Instant instant) {
        return instant.toEpochMilli();
    }

    private AgentException unavailable(RuntimeException cause) {
        return new AgentException(AgentErrorCode.CHAT_HISTORY_UNAVAILABLE);
    }

    private String userConversationsKey(long userId) {
        return PREFIX + "user:" + userId + ":conversations";
    }

    private String baseKey(String scoped) {
        return PREFIX + "conversation:" + scoped;
    }

    private String messagesKey(String scoped) {
        return baseKey(scoped) + ":messages";
    }

    private String payloadsKey(String scoped) {
        return baseKey(scoped) + ":payloads";
    }

    private String sequenceKey(String scoped) {
        return baseKey(scoped) + ":sequence";
    }

    private String metaKey(String scoped) {
        return baseKey(scoped) + ":meta";
    }

    private String actionsKey(String scoped) {
        return baseKey(scoped) + ":actions";
    }

    private String turnsKey(String scoped) {
        return baseKey(scoped) + ":turns";
    }

    private String summaryKey(String scoped) {
        return baseKey(scoped) + ":summary";
    }
}
