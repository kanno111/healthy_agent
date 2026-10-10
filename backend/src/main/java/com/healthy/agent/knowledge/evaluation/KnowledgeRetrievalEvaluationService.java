package com.healthy.agent.knowledge.evaluation;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.search.KnowledgeSearchHit;
import com.healthy.agent.knowledge.search.KnowledgeSearchRequest;
import com.healthy.agent.knowledge.search.KnowledgeSearchResponse;
import com.healthy.agent.knowledge.search.KnowledgeSearchService;
import com.healthy.agent.knowledge.search.KnowledgeSearchStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class KnowledgeRetrievalEvaluationService {
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 20;

    private final KnowledgeSearchService searchService;
    private final KnowledgeRetrievalEvaluationDatasetLoader datasetLoader;
    private final Clock clock;

    @Autowired
    public KnowledgeRetrievalEvaluationService(
            KnowledgeSearchService searchService,
            KnowledgeRetrievalEvaluationDatasetLoader datasetLoader
    ) {
        this(searchService, datasetLoader, Clock.systemUTC());
    }

    KnowledgeRetrievalEvaluationService(
            KnowledgeSearchService searchService,
            KnowledgeRetrievalEvaluationDatasetLoader datasetLoader,
            Clock clock
    ) {
        this.searchService = searchService;
        this.datasetLoader = datasetLoader;
        this.clock = clock;
    }

    public KnowledgeRetrievalEvaluationResponse evaluate(KnowledgeRetrievalEvaluationRequest request) {
        KnowledgeSearchStrategy strategy = request == null || request.strategy() == null
                ? KnowledgeSearchStrategy.BM25 : request.strategy();
        int limit = normalizeLimit(request == null ? null : request.limit());
        KnowledgeRetrievalEvaluationDataset dataset = datasetLoader.get();

        List<KnowledgeRetrievalEvaluationCaseResult> results = new ArrayList<>();
        double recall = 0.0d;
        double precision = 0.0d;
        int hits = 0;
        double reciprocalRank = 0.0d;
        double ndcg = 0.0d;
        int positiveCases = 0;
        int negativeCases = 0;
        int emptyNegativeCases = 0;

        for (KnowledgeRetrievalEvaluationCase evaluationCase : dataset.cases()) {
            KnowledgeSearchResponse search = searchService.search(new KnowledgeSearchRequest(
                    evaluationCase.question(), limit, strategy));
            KnowledgeRetrievalEvaluationCaseResult result = evaluateCase(
                    evaluationCase, search.results(), limit);
            results.add(result);
            if (evaluationCase.shouldAnswer()) {
                positiveCases++;
                recall += result.recallAtK();
                precision += result.precisionAtK();
                if (result.hitAtK()) {
                    hits++;
                }
                reciprocalRank += result.reciprocalRank();
                ndcg += result.ndcgAtK();
            } else {
                negativeCases++;
                if (search.results().isEmpty()) {
                    emptyNegativeCases++;
                }
            }
        }

        return new KnowledgeRetrievalEvaluationResponse(
                dataset.version(), strategy, limit, dataset.cases().size(),
                positiveCases, negativeCases,
                average(recall, positiveCases),
                average(precision, positiveCases),
                average(hits, positiveCases),
                average(reciprocalRank, positiveCases),
                average(ndcg, positiveCases),
                average(emptyNegativeCases, negativeCases),
                Instant.now(clock), results);
    }

    private KnowledgeRetrievalEvaluationCaseResult evaluateCase(
            KnowledgeRetrievalEvaluationCase evaluationCase,
            List<KnowledgeSearchHit> hits,
            int limit
    ) {
        Set<String> expected = Set.copyOf(evaluationCase.expectedFileNames());
        List<String> returned = distinctFileNames(hits);
        if (!evaluationCase.shouldAnswer()) {
            return new KnowledgeRetrievalEvaluationCaseResult(
                    evaluationCase.id(), evaluationCase.category(), evaluationCase.question(), false,
                    evaluationCase.expectedFileNames(), returned, 0,
                    false,
                    0.0d, 0.0d, 0.0d, 0.0d);
        }

        int relevant = 0;
        int firstRelevantRank = 0;
        double dcg = 0.0d;
        for (int index = 0; index < returned.size(); index++) {
            if (!expected.contains(returned.get(index))) {
                continue;
            }
            relevant++;
            if (firstRelevantRank == 0) {
                firstRelevantRank = index + 1;
            }
            dcg += discount(index + 1);
        }

        double idealDcg = 0.0d;
        int idealRelevant = Math.min(expected.size(), limit);
        for (int rank = 1; rank <= idealRelevant; rank++) {
            idealDcg += discount(rank);
        }

        return new KnowledgeRetrievalEvaluationCaseResult(
                evaluationCase.id(), evaluationCase.category(), evaluationCase.question(), true,
                evaluationCase.expectedFileNames(), returned, firstRelevantRank,
                firstRelevantRank > 0,
                (double) relevant / expected.size(),
                (double) relevant / limit,
                firstRelevantRank == 0 ? 0.0d : 1.0d / firstRelevantRank,
                idealDcg == 0.0d ? 0.0d : dcg / idealDcg);
    }

    private List<String> distinctFileNames(List<KnowledgeSearchHit> hits) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (KnowledgeSearchHit hit : hits) {
            names.add(hit.fileName());
        }
        return List.copyOf(names);
    }

    private double discount(int rank) {
        return 1.0d / (Math.log(rank + 1.0d) / Math.log(2.0d));
    }

    private double average(double total, int count) {
        return count == 0 ? 0.0d : total / count;
    }

    private int normalizeLimit(Integer limit) {
        int normalized = limit == null ? DEFAULT_LIMIT : limit;
        if (normalized < 1 || normalized > MAX_LIMIT) {
            throw new AgentException(AgentErrorCode.INVALID_SEARCH_LIMIT);
        }
        return normalized;
    }
}
