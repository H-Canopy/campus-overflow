package com.campusoverflow.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.discovery.domain.search.SearchDocument;
import com.campusoverflow.discovery.domain.search.SearchIndex;
import com.campusoverflow.discovery.domain.search.SearchIndex.RelatedCandidate;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.ContentDeletedEvent;
import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.shared.event.ProcessedEventStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 评审补测：SearchIndexProjector 各事件分支与幂等（用 Fake 仓储，无需容器）。 */
class SearchIndexProjectorTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private FakeSearchIndex index;
    private FakeProcessedEventStore processed;
    private SearchIndexProjector projector;

    @BeforeEach
    void setUp() {
        index = new FakeSearchIndex();
        processed = new FakeProcessedEventStore();
        projector = new SearchIndexProjector(index, processed);
    }

    @Test
    void answerSubmittedIncrementsAnswerCount() {
        projector.on(new AnswerSubmittedEvent("evt-1", NOW, 101, 7, 9, 8, "标题"));
        assertThat(index.answerCounts).containsEntry(7L, 1L);
    }

    @Test
    void contentDeletedAnswerDecrementsAnswerCount() {
        projector.on(new ContentDeletedEvent("evt-2", NOW, "ANSWER", 101, 7));
        assertThat(index.answerCounts).containsEntry(7L, -1L);
        assertThat(index.deleted).isEmpty();
    }

    @Test
    void contentDeletedQuestionMarksDeleted() {
        projector.on(new ContentDeletedEvent("evt-3", NOW, "QUESTION", 7, 7));
        assertThat(index.deleted).contains(7L);
        assertThat(index.answerCounts).isEmpty();
    }

    @Test
    void contentDeletedUnknownTypeIsIgnored() {
        projector.on(new ContentDeletedEvent("evt-4", NOW, "COMMENT", 5, 7));
        assertThat(index.answerCounts).isEmpty();
        assertThat(index.deleted).isEmpty();
    }

    @Test
    void voteOnQuestionAdjustsScoreByDelta() {
        projector.on(new VoteCastEvent("evt-5", NOW, "QUESTION", 7, 8, 9, 7, 0, 1));
        assertThat(index.scores).containsEntry(7L, 1L);
        // 第二次改投：old=1 → new=-1，delta = new-old = -2，累计 1 + (-2) = -1
        projector.on(new VoteCastEvent("evt-6", NOW, "QUESTION", 7, 8, 9, 7, 1, -1));
        assertThat(index.scores).containsEntry(7L, -1L);
    }

    @Test
    void voteOnAnswerDoesNotAdjustQuestionScoreButIsMarked() {
        projector.on(new VoteCastEvent("evt-7", NOW, "ANSWER", 101, 8, 9, 7, 0, 1));
        assertThat(index.scores).isEmpty();
        // 事件仍被幂等守卫标记（对所有事件生效），第二次投递返回 false。
        assertThat(processed.markProcessed("evt-7", SearchIndexProjector.CONSUMER)).isFalse();
    }

    @Test
    void duplicateEventIsIdempotent() {
        AnswerSubmittedEvent e = new AnswerSubmittedEvent("dup", NOW, 101, 7, 9, 8, "标题");
        projector.on(e);
        projector.on(e);
        assertThat(index.answerCounts).containsEntry(7L, 1L);
    }

    @Test
    void answerAcceptedMarksAccepted() {
        projector.on(new AnswerAcceptedEvent("evt-8", NOW, 101, 7, 9, 8, 5, "标题"));
        assertThat(index.accepted).contains(7L);
    }

    // ---- fakes ----

    private static final class FakeSearchIndex implements SearchIndex {
        final Map<Long, Long> answerCounts = new HashMap<>();
        final Map<Long, Long> scores = new HashMap<>();
        final Set<Long> accepted = new HashSet<>();
        final Set<Long> deleted = new HashSet<>();

        @Override public void insert(SearchDocument document) { }
        @Override public void updateText(long questionId, String title, String bodyText, List<String> tags, Instant at) { }
        @Override public void adjustAnswerCount(long questionId, int delta, Instant at) {
            answerCounts.merge(questionId, (long) delta, Long::sum);
        }
        @Override public void adjustScore(long questionId, int delta) {
            scores.merge(questionId, (long) delta, Long::sum);
        }
        @Override public void markAccepted(long questionId, Instant at) { accepted.add(questionId); }
        @Override public void setBountyOpen(long questionId, boolean open) { }
        @Override public void markDeleted(long questionId) { deleted.add(questionId); }
        @Override public List<String> findTags(long questionId) { return List.of(); }
        @Override public List<RelatedCandidate> findCandidatesSharingTags(long questionId, List<String> tags, int limit) {
            return List.of();
        }
    }

    private static final class FakeProcessedEventStore implements ProcessedEventStore {
        final Set<String> processed = new HashSet<>();

        @Override public boolean markProcessed(String eventId, String consumer) {
            return processed.add(eventId + "|" + consumer);
        }
    }
}
