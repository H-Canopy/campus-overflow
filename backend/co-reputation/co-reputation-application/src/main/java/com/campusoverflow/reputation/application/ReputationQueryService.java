package com.campusoverflow.reputation.application;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.reputation.domain.BadgeRepository;
import com.campusoverflow.reputation.domain.LedgerEntry;
import com.campusoverflow.reputation.domain.LedgerRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReputationQueryService {

    private final ReputationAccountRepository accounts;
    private final LedgerRepository ledger;
    private final BadgeRepository badges;
    private final IdentityApi identity;

    public ReputationQueryService(ReputationAccountRepository accounts, LedgerRepository ledger,
                                  BadgeRepository badges, IdentityApi identity) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.badges = badges;
        this.identity = identity;
    }

    public ReputationProfileView profile(long userId, Actor viewerOrNull) {
        ReputationAccount account = accounts.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("REP-4001", "该用户尚未建立声誉账户"));

        boolean self = viewerOrNull != null && viewerOrNull.is(userId);
        // 积分属于个人数据：仅本人可见，他人查看时为 null
        Integer available = self ? account.availablePoints() : null;
        Integer frozen = self ? account.frozenPoints() : null;

        UserSummary user = identity.findUser(userId)
                .orElseThrow(() -> new NotFoundException("REP-4002", "用户不存在"));

        List<ReputationProfileView.BadgeView> badgeViews = badges.findByUser(userId).stream()
                .map(award -> new ReputationProfileView.BadgeView(award.type().name(),
                        award.type().displayName(), award.type().description(), award.awardedAt()))
                .toList();

        return new ReputationProfileView(userId, user.displayName(), account.reputation(), available, frozen,
                badgeViews);
    }

    /** 声誉流水属于个人数据：仅本人与管理员可查看。 */
    public PageResult<LedgerEntryView> ledger(long userId, Actor viewer, PageRequest page) {
        boolean allowed = viewer != null && (viewer.is(userId) || viewer.isAdmin());
        if (!allowed) {
            throw new ForbiddenException("REP-3002", "只能查看自己的声誉流水");
        }
        PageResult<LedgerEntry> result = ledger.findByUser(userId, page);
        return result.map(entry -> new LedgerEntryView(entry.id(), entry.reason().name(), entry.reason().label(),
                entry.delta(), entry.refType(), entry.refId(), entry.createdAt()));
    }
}
