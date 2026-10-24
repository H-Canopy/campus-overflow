package com.campusoverflow.discovery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.discovery.domain.notification.MentionParser;
import com.campusoverflow.discovery.domain.notification.Notification;
import com.campusoverflow.discovery.domain.notification.NotificationType;
import com.campusoverflow.discovery.domain.search.RelatedQuestionPolicy;
import com.campusoverflow.discovery.domain.search.SearchIndex.RelatedCandidate;
import com.campusoverflow.discovery.domain.search.SearchQuery;
import com.campusoverflow.discovery.domain.search.SearchSort;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ForbiddenException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryDomainTest {

    @Test
    void searchQueryStripsOperatorsAndPicksDefaultSort() {
        SearchQuery q = new SearchQuery("  +红黑树 -旋转*  ", null, " Java ", null, false, false, null);
        assertThat(q.terms()).containsExactly("红黑树", "旋转");
        assertThat(q.sort()).isEqualTo(SearchSort.RELEVANCE);
        assertThat(q.tag()).isEqualTo("java");

        SearchQuery noKeyword = new SearchQuery("   ", null, null, SearchSort.RELEVANCE, false, false, null);
        assertThat(noKeyword.keyword()).isNull();
        assertThat(noKeyword.sort()).isEqualTo(SearchSort.NEWEST);
    }

    @Test
    void singleCharacterTermsAreRejectedBecauseOfNgramTokenSize() {
        assertThatThrownBy(() -> new SearchQuery("树", null, null, null, false, false, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void jaccardRanking() {
        assertThat(RelatedQuestionPolicy.jaccard(2, 3, 3)).isEqualTo(0.5);
        List<RelatedCandidate> ranked = RelatedQuestionPolicy.rank(List.of(
                new RelatedCandidate(1, "A", 1, 5, 99, 3, true),   // J = 1/7
                new RelatedCandidate(2, "B", 2, 2, 0, 0, false),   // J = 2/3
                new RelatedCandidate(3, "C", 2, 3, 5, 1, true)     // J = 2/4
        ), 3, 2);
        assertThat(ranked).extracting(RelatedCandidate::questionId).containsExactly(2L, 3L);
    }

    @Test
    void sameJaccardScoreSortsByScoreDescending() {
        // 回归测试：相似度相同时，score 高的必须在前（之前 Comparator.reversed() 链反转导致方向反了）
        // ownTagCount = 3
        // X: sharedTags=2, tagCount=3 → J = 2/(3+3-2) = 2/4 = 0.5，score=1
        // Y: sharedTags=2, tagCount=3 → J = 0.5，score=100
        // Z: sharedTags=2, tagCount=3 → J = 0.5，score=50
        // RelatedCandidate 签名：(questionId, title, sharedTags, tagCount, score, answerCount, accepted)
        List<RelatedCandidate> ranked = RelatedQuestionPolicy.rank(List.of(
                new RelatedCandidate(1, "X", 2, 3, 1, 0, false),
                new RelatedCandidate(2, "Y", 2, 3, 100, 0, false),
                new RelatedCandidate(3, "Z", 2, 3, 50, 0, false)
        ), 3, 3);
        assertThat(ranked).extracting(RelatedCandidate::questionId).containsExactly(2L, 3L, 1L);
    }

    @Test
    void sameJaccardAndScoreSortsByAcceptedFirst() {
        // 相似度和 score 都相同时，已采纳的在前
        // ownTagCount = 3
        // A: J=0.5, score=10, accepted=false
        // B: J=0.5, score=10, accepted=true
        List<RelatedCandidate> ranked = RelatedQuestionPolicy.rank(List.of(
                new RelatedCandidate(1, "A", 2, 3, 10, 0, false),
                new RelatedCandidate(2, "B", 2, 3, 10, 0, true)
        ), 3, 2);
        assertThat(ranked).extracting(RelatedCandidate::questionId).containsExactly(2L, 1L);
    }

    @Test
    void mentionsAreParsedDeduplicatedAndCapped() {
        assertThat(MentionParser.extract("感谢 @王老师，另外 @小明 @小明 你看看")).containsExactly("王老师", "小明");
        assertThat(MentionParser.extract("@a1 @b2 @c3 @d4 @e5 @f6")).hasSize(MentionParser.MAX_MENTIONS);
        assertThat(MentionParser.extract("邮箱 a@b 不算")).isEmpty();
    }

    @Test
    void onlyRecipientCanMarkNotificationRead() {
        Notification n = Notification.create(7, NotificationType.ANSWER_RECEIVED, "你的问题收到新回答", "/questions/1",
                "evt-1", Instant.now());
        assertThatThrownBy(() -> n.markRead(8)).isInstanceOf(ForbiddenException.class);
        n.markRead(7);
        assertThat(n.read()).isTrue();
    }
}
