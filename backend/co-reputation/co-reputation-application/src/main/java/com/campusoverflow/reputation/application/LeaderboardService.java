package com.campusoverflow.reputation.application;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.reputation.api.event.ReputationChangedEvent;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.shared.event.ProcessedEventStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 排行榜：周/月/学期榜来自 Redis ZSET，总榜直接读账户表。 */
@Service
public class LeaderboardService {

    /** 消费者标识，与 processed_event 表的 consumer 列对应。 */
    private static final String CONSUMER = "reputation.leaderboard";

    private final LeaderboardStore store;
    private final ReputationAccountRepository accounts;
    private final IdentityApi identity;
    private final ProcessedEventStore processed;
    private final Clock clock;

    public LeaderboardService(LeaderboardStore store, ReputationAccountRepository accounts, IdentityApi identity,
                              ProcessedEventStore processed, Clock clock) {
        this.store = store;
        this.accounts = accounts;
        this.identity = identity;
        this.processed = processed;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void on(ReputationChangedEvent e) {
        // 幂等：同一事件重复投递时只累加一次（至少一次投递语义，ADR-002）
        if (!processed.markProcessed(e.eventId(), CONSUMER)) {
            return;
        }
        if (e.delta() == 0) {
            return;
        }
        store.increment(e.userId(), e.delta(), e.occurredAt());
    }

    @Transactional(readOnly = true)
    public List<LeaderboardEntryView> top(LeaderboardPeriod period, int limit) {
        int size = Math.max(limit, 1);
        List<LeaderboardStore.Entry> entries;
        if (period == LeaderboardPeriod.ALL) {
            // 总榜不由 Redis 提供，直接读账户表（按声誉倒序）
            entries = accounts.topByReputation(size).stream()
                    .map(account -> new LeaderboardStore.Entry(account.userId(), account.reputation()))
                    .toList();
        } else {
            entries = store.top(period, clock.instant(), size);
        }

        Map<Long, UserSummary> users = identity.findUsers(
                entries.stream().map(LeaderboardStore.Entry::userId).toList());

        List<LeaderboardEntryView> result = new ArrayList<>(entries.size());
        int rank = 1;
        for (LeaderboardStore.Entry entry : entries) {
            UserSummary user = users.get(entry.userId());
            result.add(new LeaderboardEntryView(rank++, entry.userId(),
                    user == null ? "用户" + entry.userId() : user.displayName(),
                    user != null && user.verified(), entry.score()));
        }
        return result;
    }
}
