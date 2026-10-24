package com.campusoverflow.reputation.application;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.qa.api.QaQueryApi;
import com.campusoverflow.qa.api.QuestionRef;
import com.campusoverflow.reputation.api.event.BountyAwardedEvent;
import com.campusoverflow.reputation.api.event.BountyClosedEvent;
import com.campusoverflow.reputation.api.event.BountyOpenedEvent;
import com.campusoverflow.reputation.domain.Bounty;
import com.campusoverflow.reputation.domain.BountyRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.shared.audit.AuditTrail;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.ConflictException;
import com.campusoverflow.shared.domain.ForbiddenException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 用例：悬赏的发起、结算、到期退款。积分冻结与悬赏状态在同一事务中变更。 */
@Service
public class BountyService {

    /** 单次到期扫描处理的悬赏数量上限，避免长事务。 */
    private static final int EXPIRE_BATCH_SIZE = 100;

    private final BountyRepository bounties;
    private final ReputationAccountRepository accounts;
    private final ReputationLedgerService ledgerService;
    private final QaQueryApi qa;
    private final IdentityApi identity;
    private final IntegrationEventPublisher events;
    private final AuditTrail audit;
    private final Clock clock;

    public BountyService(BountyRepository bounties, ReputationAccountRepository accounts,
                         ReputationLedgerService ledgerService, QaQueryApi qa, IdentityApi identity,
                         IntegrationEventPublisher events, AuditTrail audit, Clock clock) {
        this.bounties = bounties;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.qa = qa;
        this.identity = identity;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public long open(Actor actor, long questionId, int points, int days) {
        QuestionRef question = qa.findQuestion(questionId)
                .orElseThrow(() -> new NotFoundException("REP-4001", "问题不存在或已删除"));
        if (question.acceptedAnswerId() != null) {
            throw new BusinessRuleException("REP-1005", "问题已有采纳答案，不能再发起悬赏");
        }

        boolean managesCourse = identity.canManageCourse(actor.userId(), question.courseId());
        if (!managesCourse) {
            throw new ForbiddenException("REP-3001", "只有课程教师、助教或管理员可以发起悬赏");
        }

        Instant now = clock.instant();
        Bounty bounty = Bounty.open(questionId, true, actor, managesCourse, points, days, now);

        ReputationAccount sponsor = accounts.findByUserId(actor.userId())
                .orElseThrow(() -> new NotFoundException("REP-4002", "您的声誉账户不存在，无法发起悬赏"));
        sponsor.freezePoints(points);
        accounts.save(sponsor);

        Bounty saved = bounties.save(bounty);

        events.publish(new BountyOpenedEvent(EventIds.next(), now, saved.id(), questionId, actor.userId(), points,
                saved.expiresAt()));
        audit.record("BOUNTY_OPENED", actor.userId(), "BOUNTY", String.valueOf(saved.id()),
                "points=" + points + ", days=" + days);
        return saved.id();
    }

    /** 由 AnswerAccepted 事件触发（在处理器事务内）。幂等：悬赏已结束则直接返回。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleOnAccept(long questionId, long answerId, long winnerId, String sourceEventId) {
        Bounty bounty = bounties.findOpenByQuestion(questionId).orElse(null);
        if (bounty == null) {
            // 幂等：没有开放中的悬赏（未发起、已结算或已到期）
            return;
        }
        if (bounty.sponsorId() == winnerId) {
            // 发起人自己的回答被采纳：关闭并退款，不发放悬赏
            ReputationAccount sponsor = accounts.findByUserId(bounty.sponsorId())
                    .orElseThrow(() -> new NotFoundException("REP-4002", "悬赏发起人的声誉账户不存在"));
            closeAndRefund(bounty, sponsor, clock.instant());
            return;
        }

        Instant now = clock.instant();
        bounty.award(answerId, winnerId, now);
        bounties.save(bounty);

        // 悬赏积分从发起人冻结账户转出，以声誉形式记入获奖者账本
        ReputationAccount sponsor = accounts.findByUserId(bounty.sponsorId())
                .orElseThrow(() -> new NotFoundException("REP-4002", "悬赏发起人的声誉账户不存在"));
        sponsor.spendFrozen(bounty.points());
        accounts.save(sponsor);

        ledgerService.record(winnerId, sourceEventId, ReputationReason.BOUNTY_AWARDED, bounty.points(), "BOUNTY",
                bounty.id());

        events.publish(new BountyAwardedEvent(EventIds.next(), now, bounty.id(), questionId, bounty.sponsorId(),
                winnerId, bounty.points()));
        audit.record("BOUNTY_AWARDED", null, "BOUNTY", String.valueOf(bounty.id()),
                "winner=" + winnerId + ", points=" + bounty.points());
    }

    /** 到期未采纳的悬赏：关闭并退回冻结积分。由定时任务调用。 */
    @Transactional
    public int expireDue() {
        Instant now = clock.instant();
        int expired = 0;
        for (Bounty bounty : bounties.findDue(now, EXPIRE_BATCH_SIZE)) {
            ReputationAccount sponsor = accounts.findByUserId(bounty.sponsorId())
                    .orElseThrow(() -> new NotFoundException("REP-4002", "悬赏发起人的声誉账户不存在"));
            closeAndRefund(bounty, sponsor, now);
            expired++;
        }
        return expired;
    }

    @Transactional(readOnly = true)
    public Optional<BountyView> latest(long questionId) {
        return bounties.findLatestByQuestion(questionId).map(bounty -> {
            String sponsorName = identity.findUser(bounty.sponsorId())
                    .map(UserSummary::displayName)
                    .orElse("用户" + bounty.sponsorId());
            return new BountyView(bounty.id(), bounty.questionId(), bounty.sponsorId(), sponsorName,
                    bounty.points(), bounty.status().name(), bounty.createdAt(), bounty.expiresAt(),
                    bounty.winnerId(), bounty.answerId());
        });
    }

    private void closeAndRefund(Bounty bounty, ReputationAccount sponsor, Instant now) {
        bounty.close(now);
        bounties.save(bounty);

        sponsor.refundFrozen(bounty.points());
        accounts.save(sponsor);

        events.publish(new BountyClosedEvent(EventIds.next(), now, bounty.id(), bounty.questionId(),
                bounty.sponsorId(), bounty.points()));
        audit.record("BOUNTY_CLOSED", null, "BOUNTY", String.valueOf(bounty.id()),
                "refunded=" + bounty.points());
    }
}
