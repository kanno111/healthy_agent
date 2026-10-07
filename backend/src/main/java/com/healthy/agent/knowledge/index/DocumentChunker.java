package com.healthy.agent.knowledge.index;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentChunker {
    private static final String BOUNDARIES = "\n。！？；.!?;";

    private final int maxCharacters;
    private final int overlapCharacters;

    public DocumentChunker(
            @Value("${agent.knowledge.indexing.chunk-max-characters:1000}") int maxCharacters,
            @Value("${agent.knowledge.indexing.chunk-overlap-characters:100}") int overlapCharacters
    ) {
        if (maxCharacters < 100 || overlapCharacters < 0 || overlapCharacters >= maxCharacters) {
            throw new IllegalArgumentException("Chunk size configuration is invalid");
        }
        this.maxCharacters = maxCharacters;
        this.overlapCharacters = overlapCharacters;
    }

    public List<DocumentChunk> split(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(start + maxCharacters, normalized.length());
            int end = hardEnd;
            if (hardEnd < normalized.length()) {
                int preferredStart = start + maxCharacters / 2;
                int boundary = findLastBoundary(normalized, preferredStart, hardEnd);
                if (boundary >= preferredStart) {
                    end = boundary + 1;
                }
            }

            String content = normalized.substring(start, end).trim();
            if (!content.isEmpty()) {
                chunks.add(new DocumentChunk(chunks.size(), content));
            }
            if (end >= normalized.length()) {
                break;
            }
            int nextStart = Math.max(start + 1, end - overlapCharacters);
            while (nextStart < end && Character.isWhitespace(normalized.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart;
        }
        return List.copyOf(chunks);
    }

    private int findLastBoundary(String text, int minimum, int maximumExclusive) {
        for (int index = maximumExclusive - 1; index >= minimum; index--) {
            if (BOUNDARIES.indexOf(text.charAt(index)) >= 0) {
                return index;
            }
        }
        return -1;
    }
}
