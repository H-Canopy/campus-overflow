package com.campusoverflow.reputation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.identity.api.CourseSummary;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.reputation.api.ReputationSummary;
import com.campusoverflow.reputation.api.event.ReputationChangedEvent;
import com.campusoverflow.reputation.domain.BadgeAward;
import com.campusoverflow.reputation.domain.BadgeRepository;
import com.campusoverflow.reputation.domain.BadgeType;
import com.campusoverflow.reputation.domain.LedgerEntry;
import com.campusoverflow.reputation.domain.LedgerRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.ProcessedEventStore;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reputation 应用层用例测试（Dev+QA 职责范围）。
 *
 * <p>本测试使用 {@code ReputationAccount.reconstitute} 构造账户对象，
 * 绕开 {@code ReputationAccount.open}（① PO 的分工），因此可在他人实现合入前独立验证
 * 应用层的编排逻辑：对账、摘要查询、排行榜、档案与流水权限。</p>
 *
 * <p>{@code BountyService} 与 {@code ReputationEventHandlers} 的测试将在
 * ① 的 {@code ReputationRulebook} / {@code ReputationAccount} 与 ② 的
 * {@code Bounty} / {@code BadgePolicy} 领域方法合入后补充——它们直接依赖这些静态工厂。</p>
 */
class ReputationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final InMemoryBadges badges = new InMemoryBadges();
    private final InMemoryLeaderboardStore leaderboardStore = new InMemoryLeaderboardStore();
    private final RecordingProcessed processed = new RecordingProcessed();
    private final StubIdentity identity = new StubIdentity();

    private static final Actor MING = new Actor(1, "小明", Role.STUDENT);
    private static final Actor ADMIN = new Actor(9, "王老师", Role.ADMIN);

    @BeforeEach
    void setUp() {
        identity.withUser(1, "小明", Role.STUDENT);
        identity.withUser(2, "小红", Role.STUDENT);
        identity.withUser(9, "王老师", Role.ADMIN);
        accounts.save(ReputationAccount.reconstitute(1L, 120, 50, 10, 0L));
        accounts.save(ReputationAccount.reconstitute(2L, 15, 0, 0, 0L));
    }

    // ---------- 对账（FF-8） ----------

    @Test
    @DisplayName("对账：余额与流水之和一致时无差异")
    void reconcileReportsNoMismatchWhenConsistent() {
        ledger.sumByUser.put(1L, 120L);
        ledger.sumByUser.put(2L, 15L);

        assertThat(new ReconciliationService(accounts, ledger).reconcile()).isEmpty();
    }

    @Test
    @DisplayName("对账：余额与流水不一致时报告差异")
    void reconcileDetectsMismatch() {
        ledger.sumByUser.put(1L, 100L);
        ledger.sumByUser.put(2L, 15L);

        List<ReconciliationService.Mismatch> mismatches = new ReconciliationService(accounts, ledger).reconcile();

        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0).userId()).isEqualTo(1L);
        assertThat(mismatches.get(0).accountReputation()).isEqualTo(120);
        assertThat(mismatches.get(0).ledgerSum()).isEqualTo(100L);
    }

    @Test
    @DisplayName("对账：没有流水的账户视为 0")
    void reconcileTreatsMissingLedgerAsZero() {
        assertThat(new ReconciliationService(accounts, ledger).reconcile())
                .extracting(ReconciliationService.Mismatch::userId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    // ---------- 摘要查询 ----------

    @Test
    @DisplayName("摘要：返回账户声誉值")
    void summaryReturnsReputation() {
        assertThat(new ReputationApiImpl(accounts).summary(1L).reputation()).isEqualTo(120);
    }

    @Test
    @DisplayName("摘要：账户不存在时返回 0")
    void summaryReturnsZeroForUnknownUser() {
        assertThat(new ReputationApiImpl(accounts).summary(999L).reputation()).isZero();
    }

    @Test
    @DisplayName("批量声誉：只返回已存在的账户")
    void reputationsReturnsExistingAccountsOnly() {
        Map<Long, Integer> result = new ReputationApiImpl(accounts).reputations(List.of(1L, 2L, 999L));

        assertThat(result).containsEntry(1L, 120).containsEntry(2L, 15);
        assertThat(result).doesNotContainKey(999L);
    }

    @Test
    @DisplayName("批量声誉：入参为空时返回空映射")
    void reputationsHandlesEmptyInput() {
        assertThat(new ReputationApiImpl(accounts).reputations(List.of())).isEmpty();
    }

    // ---------- 排行榜 ----------

    @Test
    @DisplayName("排行榜：总榜直接读账户表，按声誉倒序并给出名次")
    void topForAllPeriodReadsAccounts() {
        List<LeaderboardEntryView> entries = new LeaderboardService(leaderboardStore, accounts, identity, processed,
                clock).top(LeaderboardPeriod.ALL, 10);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).rank()).isEqualTo(1);
        assertThat(entries.get(0).userId()).isEqualTo(1L);
        assertThat(entries.get(0).score()).isEqualTo(120);
        assertThat(entries.get(0).displayName()).isEqualTo("小明");
        assertThat(entries.get(1).rank()).isEqualTo(2);
    }

    @Test
    @DisplayName("排行榜：周期榜来自 Redis ZSET，不读账户表")
    void topForPeriodReadsStore() {
        leaderboardStore.seed.put(LeaderboardPeriod.WEEK, List.of(new LeaderboardStore.Entry(2L, 15)));

        List<LeaderboardEntryView> entries = new LeaderboardService(leaderboardStore, accounts, identity, processed,
                clock).top(LeaderboardPeriod.WEEK, 10);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).userId()).isEqualTo(2L);
        assertThat(entries.get(0).score()).isEqualTo(15);
    }

    @Test
    @DisplayName("排行榜：声誉变化事件累加到 ZSET")
    void leaderboardIncrementsOnReputationChanged() {
        LeaderboardService service = new LeaderboardService(leaderboardStore, accounts, identity, processed, clock);

        service.on(new ReputationChangedEvent("evt-1", NOW, 2L, 15, 15, "ANSWER_ACCEPTED"));

        assertThat(leaderboardStore.increments).containsExactly(new Object[]{2L, 15});
    }

    @Test
    @DisplayName("排行榜：重复投递同一事件不重复累加（幂等）")
    void leaderboardIsIdempotentForDuplicateEvent() {
        LeaderboardService service = new LeaderboardService(leaderboardStore, accounts, identity, processed, clock);
        ReputationChangedEvent event = new ReputationChangedEvent("evt-1", NOW, 2L, 15, 15, "ANSWER_ACCEPTED");

        service.on(event);
        service.on(event);

        assertThat(leaderboardStore.increments).hasSize(1);
    }

    // ---------- 档案与流水权限 ----------

    @Test
    @DisplayName("档案：本人可见积分字段")
    void profileShowsPointsToSelf() {
        ReputationProfileView view = new ReputationQueryService(accounts, ledger, badges, identity)
                .profile(1L, MING);

        assertThat(view.reputation()).isEqualTo(120);
        assertThat(view.availablePoints()).isEqualTo(50);
        assertThat(view.frozenPoints()).isEqualTo(10);
    }

    @Test
    @DisplayName("档案：他人查看时积分字段为 null（个人数据最小化）")
    void profileHidesPointsFromOthers() {
        ReputationProfileView view = new ReputationQueryService(accounts, ledger, badges, identity)
                .profile(1L, new Actor(2, "小红", Role.STUDENT));

        assertThat(view.reputation()).isEqualTo(120);
        assertThat(view.availablePoints()).isNull();
        assertThat(view.frozenPoints()).isNull();
    }

    @Test
    @DisplayName("档案：匿名访问也可查看声誉与徽章")
    void profileWorksForAnonymousViewer() {
        ReputationProfileView view = new ReputationQueryService(accounts, ledger, badges, identity)
                .profile(2L, null);

        assertThat(view.reputation()).isEqualTo(15);
        assertThat(view.availablePoints()).isNull();
    }

    @Test
    @DisplayName("档案：账户不存在时抛出未找到异常")
    void profileFailsForUnknownAccount() {
        assertThatThrownBy(() -> new ReputationQueryService(accounts, ledger, badges, identity).profile(999L, MING))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("流水：本人可以查看自己的流水")
    void ledgerVisibleToOwner() {
        ledger.save(LedgerEntry.of(1L, "evt-1", ReputationReason.ANSWER_ACCEPTED, 15, "ANSWER", 5L, NOW));

        PageResult<LedgerEntryView> result = new ReputationQueryService(accounts, ledger, badges, identity)
                .ledger(1L, MING, new PageRequest(1, 20));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().get(0).reason()).isEqualTo("ANSWER_ACCEPTED");
        assertThat(result.items().get(0).reasonLabel()).isEqualTo("回答被采纳");
        assertThat(result.items().get(0).delta()).isEqualTo(15);
    }

    @Test
    @DisplayName("流水：他人查看他人流水被拒绝")
    void ledgerForbiddenForOthers() {
        assertThatThrownBy(() -> new ReputationQueryService(accounts, ledger, badges, identity)
                .ledger(1L, new Actor(2, "小红", Role.STUDENT), new PageRequest(1, 20)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("流水：管理员可以查看任意用户流水")
    void ledgerVisibleToAdmin() {
        ledger.save(LedgerEntry.of(1L, "evt-1", ReputationReason.ANSWER_ACCEPTED, 15, "ANSWER", 5L, NOW));

        PageResult<LedgerEntryView> result = new ReputationQueryService(accounts, ledger, badges, identity)
                .ledger(1L, ADMIN, new PageRequest(1, 20));

        assertThat(result.total()).isEqualTo(1);
    }

    // ---------- 测试替身 ----------

    private static class InMemoryAccounts implements ReputationAccountRepository {
        private final Map<Long, ReputationAccount> store = new LinkedHashMap<>();

        @Override
        public Optional<ReputationAccount> findByUserId(long userId) {
            return Optional.ofNullable(store.get(userId));
        }

        @Override
        public List<ReputationAccount> findAllByUserId(Collection<Long> userIds) {
            return userIds.stream().map(store::get).filter(a -> a != null).toList();
        }

        @Override
        public List<ReputationAccount> topByReputation(int limit) {
            return store.values().stream()
                    .sorted((a, b) -> Integer.compare(b.reputation(), a.reputation()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ReputationAccount> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public ReputationAccount save(ReputationAccount account) {
            store.put(account.userId(), account);
            return account;
        }
    }

    private static class InMemoryLedger implements LedgerRepository {
        private final List<LedgerEntry> entries = new ArrayList<>();
        private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
        final Map<Long, Long> sumByUser = new HashMap<>();

        @Override
        public boolean exists(String eventId, long userId, ReputationReason reason) {
            return entries.stream().anyMatch(e -> e.eventId().equals(eventId) && e.userId() == userId
                    && e.reason() == reason);
        }

        @Override
        public void append(LedgerEntry entry) {
            entries.add(entry);
            sumByUser.merge(entry.userId(), (long) entry.delta(), Long::sum);
        }

        @Override
        public PageResult<LedgerEntry> findByUser(long userId, PageRequest page) {
            List<LedgerEntry> filtered = entries.stream().filter(e -> e.userId() == userId).toList();
            int from = Math.min(page.offset(), filtered.size());
            int to = Math.min(from + page.size(), filtered.size());
            List<LedgerEntry> slice = filtered.subList(from, to);
            return new PageResult<>(slice, filtered.size(), page.page(), page.size());
        }

        @Override
        public long countByUserAndReason(long userId, ReputationReason reason) {
            return entries.stream().filter(e -> e.userId() == userId && e.reason() == reason).count();
        }

        @Override
        public int sumPositiveVoteDeltaSince(long userId, Instant since) {
            return entries.stream()
                    .filter(e -> e.userId() == userId && e.reason().isVote() && e.delta() > 0
                            && !e.createdAt().isBefore(since))
                    .mapToInt(LedgerEntry::delta)
                    .sum();
        }

        @Override
        public Map<Long, Long> sumByUser() {
            return Map.copyOf(sumByUser);
        }

        void save(LedgerEntry entry) {
            // 模拟持久化层回填自增主键
            entries.add(new LedgerEntry(sequence.incrementAndGet(), entry.userId(), entry.eventId(), entry.reason(),
                    entry.delta(), entry.refType(), entry.refId(), entry.createdAt()));
        }
    }

    private static class InMemoryBadges implements BadgeRepository {
        private final List<BadgeAward> awards = new ArrayList<>();

        @Override
        public boolean has(long userId, BadgeType type) {
            return awards.stream().anyMatch(a -> a.userId() == userId && a.type() == type);
        }

        @Override
        public void award(BadgeAward award) {
            awards.add(award);
        }

        @Override
        public List<BadgeAward> findByUser(long userId) {
            return awards.stream().filter(a -> a.userId() == userId).toList();
        }
    }

    private static class InMemoryLeaderboardStore implements LeaderboardStore {
        final List<Object[]> increments = new ArrayList<>();
        final Map<LeaderboardPeriod, List<Entry>> seed = new HashMap<>();

        @Override
        public void increment(long userId, int delta, Instant at) {
            increments.add(new Object[]{userId, delta});
        }

        @Override
        public List<Entry> top(LeaderboardPeriod period, Instant at, int limit) {
            return seed.getOrDefault(period, List.of());
        }
    }

    private static class RecordingProcessed implements ProcessedEventStore {
        private final Map<String, Boolean> seen = new HashMap<>();

        @Override
        public boolean markProcessed(String eventId, String consumer) {
            return seen.putIfAbsent(eventId + "|" + consumer, Boolean.TRUE) == null;
        }
    }

    private static class StubIdentity implements IdentityApi {
        private final Map<Long, UserSummary> users = new HashMap<>();

        StubIdentity withUser(long id, String name, Role role) {
            users.put(id, new UserSummary(id, name, role, true, "计算机工程学院"));
            return this;
        }

        @Override
        public Optional<UserSummary> findUser(long userId) {
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public Map<Long, UserSummary> findUsers(Collection<Long> userIds) {
            Map<Long, UserSummary> result = new HashMap<>();
            userIds.forEach(id -> findUser(id).ifPresent(u -> result.put(id, u)));
            return result;
        }

        @Override
        public Optional<UserSummary> findByDisplayName(String displayName) {
            return users.values().stream().filter(u -> u.displayName().equals(displayName)).findFirst();
        }

        @Override
        public Optional<CourseSummary> findCourse(long courseId) {
            return Optional.of(new CourseSummary(courseId, "CS2001", "数据结构", "2026-秋", 100L));
        }

        @Override
        public boolean canManageCourse(long userId, Long courseId) {
            return false;
        }
    }
}
