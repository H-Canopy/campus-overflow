package com.campusoverflow.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.discovery.domain.search.SearchDocument;
import com.campusoverflow.discovery.domain.search.SearchHit;
import com.campusoverflow.discovery.domain.search.SearchIndex;
import com.campusoverflow.discovery.domain.search.SearchPort;
import com.campusoverflow.discovery.domain.search.SearchQuery;
import com.campusoverflow.identity.api.CourseSummary;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscoveryQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    private FakeSearchPort search;
    private FakeSearchIndex index;
    private FakeIdentityApi identity;
    private DiscoveryQueryService service;

    @BeforeEach
    void setUp() {
        search = new FakeSearchPort();
        index = new FakeSearchIndex();
        identity = new FakeIdentityApi();
        service = new DiscoveryQueryService(search, index, identity);
    }

    @Test
    @DisplayName("搜索：命中结果映射为视图，作者信息批量回源")
    void searchMapsHitsAndResolvesAuthorsInOneCall() {
        identity.users.put(1L, new UserSummary(1L, "王小明", Role.STUDENT, true, "计算机学院"));
        identity.users.put(2L, new UserSummary(2L, "李老师", Role.TEACHER, true, "计算机学院"));
        search.result = new PageResult<>(List.of(hit(100L, 1L, 3), hit(101L, 2L, 7)), 2, 1, 20);

        PageResult<SearchResultView> page = service.search(query());

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).id()).isEqualTo(100L);
        assertThat(page.items().get(0).author().displayName()).isEqualTo("王小明");
        assertThat(page.items().get(0).author().role()).isEqualTo("STUDENT");
        assertThat(page.items().get(1).author().displayName()).isEqualTo("李老师");
        assertThat(page.items().get(0).tags()).containsExactly("数据结构");
        assertThat(page.items().get(1).score()).isEqualTo(7);
        assertThat(identity.findUsersCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("搜索：无命中时返回空页且保留分页信息")
    void searchReturnsEmptyPageWhenNoHits() {
        search.result = PageResult.empty(1, 20);

        PageResult<SearchResultView> page = service.search(query());

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("搜索：作者已注销时降级显示而非抛错")
    void searchDegradesWhenAuthorMissing() {
        search.result = new PageResult<>(List.of(hit(100L, 99L, 0)), 1, 1, 20);

        PageResult<SearchResultView> page = service.search(query());

        assertThat(page.items().get(0).author().id()).isEqualTo(99L);
        assertThat(page.items().get(0).author().displayName()).isEqualTo("已注销用户");
        assertThat(page.items().get(0).author().verified()).isFalse();
    }

    /**
     * 只覆盖 limit = 0 的情形。
     * 负数不是「返回空」而是抛 {@link IllegalArgumentException}（{@code Stream.limit(-1)}），
     * 属于 {@code DiscoveryQueryService.related} 的入参校验缺口，已单独报告，不在此处断言以免把缺陷固化成预期。
     */
    @Test
    @DisplayName("相关问题：limit 为 0 时不返回任何候选")
    void relatedReturnsNothingForZeroLimit() {
        index.tags.put(100L, List.of("数据结构"));

        assertThat(service.related(100L, 0)).isEmpty();
    }

    @Test
    @DisplayName("相关问题：问题没有标签时返回空，不查候选")
    void relatedReturnsEmptyWithoutTags() {
        index.tags.put(100L, List.of());

        assertThat(service.related(100L, 5)).isEmpty();
        assertThat(index.findTagsCalls.get()).isEqualTo(1);
        assertThat(index.candidateCalls.get()).isZero();
    }

    /**
     * 关键词留空：{@code SearchQuery} 的紧凑构造器在带关键词时会调用 {@code terms()}，
     * 那是 ⑤ 的领域方法（当前仍为 TODO）。本测试只验证「命中结果→视图」的转换，与关键词解析无关。
     */
    private static SearchQuery query() {
        return new SearchQuery(null, null, null, null, false, false, new PageRequest(1, 20));
    }

    private static SearchHit hit(long questionId, long authorId, int score) {
        return new SearchHit(questionId, "红黑树插入后为什么必须旋转？", "变色为什么不够……", List.of("数据结构"),
                500L, authorId, score, 2, false, false, NOW);
    }

    private static class FakeSearchPort implements SearchPort {
        private PageResult<SearchHit> result = PageResult.empty(1, 20);

        @Override
        public PageResult<SearchHit> search(SearchQuery query) {
            return result;
        }
    }

    private static class FakeSearchIndex implements SearchIndex {
        private final Map<Long, List<String>> tags = new HashMap<>();
        private final AtomicInteger findTagsCalls = new AtomicInteger();
        private final AtomicInteger candidateCalls = new AtomicInteger();

        @Override
        public void insert(SearchDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateText(long questionId, String title, String bodyText, List<String> tags, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void adjustAnswerCount(long questionId, int delta, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void adjustScore(long questionId, int delta) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAccepted(long questionId, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBountyOpen(long questionId, boolean open) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markDeleted(long questionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> findTags(long questionId) {
            findTagsCalls.incrementAndGet();
            return tags.getOrDefault(questionId, List.of());
        }

        @Override
        public List<RelatedCandidate> findCandidatesSharingTags(long questionId, List<String> tags, int limit) {
            candidateCalls.incrementAndGet();
            return List.of();
        }
    }

    private static class FakeIdentityApi implements IdentityApi {
        private final Map<Long, UserSummary> users = new HashMap<>();
        private final AtomicInteger findUsersCalls = new AtomicInteger();

        @Override
        public Optional<UserSummary> findUser(long userId) {
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public Map<Long, UserSummary> findUsers(Collection<Long> userIds) {
            findUsersCalls.incrementAndGet();
            Map<Long, UserSummary> found = new HashMap<>();
            userIds.forEach(id -> {
                UserSummary user = users.get(id);
                if (user != null) {
                    found.put(id, user);
                }
            });
            return found;
        }

        @Override
        public Optional<UserSummary> findByDisplayName(String displayName) {
            return users.values().stream().filter(u -> u.displayName().equals(displayName)).findFirst();
        }

        @Override
        public Optional<CourseSummary> findCourse(long courseId) {
            return Optional.empty();
        }

        @Override
        public boolean canManageCourse(long userId, Long courseId) {
            return false;
        }
    }
}
