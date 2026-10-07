package com.healthy.agent.knowledge.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    @Test
    void keepsShortTextInOneChunk() {
        DocumentChunker chunker = new DocumentChunker(100, 10);

        List<DocumentChunk> chunks = chunker.split("患者预约成功后，请提前到院。\n\n如需取消，请及时操作。");

        assertThat(chunks).containsExactly(new DocumentChunk(
                0, "患者预约成功后，请提前到院。\n\n如需取消，请及时操作。"
        ));
    }

    @Test
    void splitsLongTextAtNaturalBoundariesWithBoundedSize() {
        DocumentChunker chunker = new DocumentChunker(100, 10);
        String text = "患者预约后请按时到院。".repeat(30);

        List<DocumentChunk> chunks = chunker.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank().hasSizeLessThanOrEqualTo(100);
            assertThat(chunk.index()).isBetween(0, chunks.size() - 1);
        });
        assertThat(chunks).extracting(DocumentChunk::index)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void returnsNoChunkForBlankText() {
        assertThat(new DocumentChunker(100, 10).split("  \n ")).isEmpty();
    }
}
