package com.campusoverflow.reputation.application;

import com.campusoverflow.identity.api.event.UserRegisteredEvent;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerEndorsedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.reputation.domain.BadgePolicy;
import com.campusoverflow.reputation.domain.BadgeType;
import com.campusoverflow.reputation.domain.LedgerRepository;
import com.campusoverflow.reputation.domain.ReputationAccount;
import com.campusoverflow.reputation.domain.ReputationAccountRepository;
import com.campusoverflow.reputation.domain.ReputationReason;
import com.campusoverflow.reputation.domain.ReputationRulebook;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订阅上游上下文的集成事件（由 OutboxRelay 在事务提交后投递，至少一次语义）。
 * 每个处理器运行在自己的事务中，并通过账本唯一键 / 徽章存在性检查实现幂等。
 */
@Component
public class ReputationEventHandlers {

    private final ReputationAccountRepository accounts;
    private final LedgerRepository ledger;
    private final ReputationLedgerService ledgerService;
    private final BadgeService badges;
    private final BountyService bounties;
    private final Clock clock;

    public ReputationEventHandlers(ReputationAccountRepository accounts, LedgerRepository ledger,
                                   ReputationLedgerService ledgerService, BadgeService badges,
                                   BountyService bounties, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.badges = badges;
        this.bounties = bounties;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void on(UserRegisteredEvent e) {
        // 开立声誉账户并发放初始悬赏积分（教师 1000，学生 0）
        if (accounts.findByUserId(e.userId()).isPresent()) {
            return;
        }
        ReputationAccount account = ReputationAccount.open(e.userId(),
                ReputationRulebook.initialPoints(e.role()));
        accounts.save(account);
    }

    /** 场景 R-2 的异步后半段：采纳 → 回答者 +15 → 徽章 → 悬赏结算。 */
    @EventListener
    @Transactional
    public void on(AnswerAcceptedEvent e) {
        int delta = ReputationRulebook.acceptedDelta(e.answerAuthorId(), e.questionAuthorId());
        if (delta != 0) {
            ledgerService.record(e.answerAuthorId(), e.eventId(), ReputationReason.ANSWER_ACCEPTED, delta, "ANSWER",
                    e.answerId());
        }
        // 采纳次数徽章：账本中 ANSWER_ACCEPTED 流水的条数即为被采纳次数（每次采纳记一笔）
        long acceptedCount = ledger.countByUserAndReason(e.answerAuthorId(),
                ReputationReason.ANSWER_ACCEPTED);
        BadgePolicy.forAcceptedCount(acceptedCount).forEach(badge -> badges.grant(e.answerAuthorId(), badge));

        // 悬赏结算：问题采纳后，悬赏积分转给回答者
        bounties.settleOnAccept(e.questionId(), e.answerId(), e.answerAuthorId(), e.eventId());
    }

    @EventListener
    @Transactional
    public void on(VoteCastEvent e) {
        // 增量 = 新效果 − 旧效果（撤销与改投都能正确处理）
        int delta = ReputationRulebook.voteDelta(e.oldValue(), e.newValue());
        if (delta == 0) {
            return;
        }
        // 自问自答不加分
        if (e.voterId() == e.targetAuthorId()) {
            return;
        }

        ReputationReason reason = "QUESTION".equals(e.targetType())
                ? ReputationReason.QUESTION_VOTE
                : ReputationReason.ANSWER_VOTE;

        int applied = delta;
        if (delta > 0) {
            // 反作弊：每人每自然日（UTC）因被投票获得的正向声誉上限
            int gainedToday = ledger.sumPositiveVoteDeltaSince(e.targetAuthorId(), startOfUtcDay());
            applied = ReputationRulebook.applyDailyCap(delta, gainedToday);
        }
        if (applied == 0) {
            return;
        }
        ledgerService.record(e.targetAuthorId(), e.eventId(), reason, applied, e.targetType(), e.targetId());
    }

    @EventListener
    @Transactional
    public void on(AnswerSubmittedEvent e) {
        // 「初来乍到」徽章：提交过回答即授予。grant 自身幂等（已拥有则忽略），重复事件不会重复授予。
        badges.grant(e.answerAuthorId(), BadgeType.FIRST_ANSWER);
    }

    @EventListener
    @Transactional
    public void on(AnswerEndorsedEvent e) {
        // 教师认证徽章：仅认证者本人不可自证
        if (e.endorserId() != e.answerAuthorId()) {
            badges.grant(e.answerAuthorId(), BadgeType.TEACHER_ENDORSED);
        }
    }

    /** UTC 自然日零点：每日上限的统计起点。 */
    private Instant startOfUtcDay() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
