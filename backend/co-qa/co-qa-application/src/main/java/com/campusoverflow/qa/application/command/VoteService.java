package com.campusoverflow.qa.application.command;

import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.qa.domain.Answer;
import com.campusoverflow.qa.domain.AnswerRepository;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.QuestionRepository;
import com.campusoverflow.qa.domain.TargetType;
import com.campusoverflow.qa.domain.Vote;
import com.campusoverflow.qa.domain.VoteRepository;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用例：投票 / 改投 / 撤销（value ∈ {-1, 0, 1}，0 表示撤销）。
 * 发布的事件同时携带旧值与新值，下游据此计算声誉增量。
 */
@Service
public class VoteService {

    private final VoteRepository votes;
    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final IntegrationEventPublisher events;
    private final Clock clock;

    public VoteService(VoteRepository votes, QuestionRepository questions, AnswerRepository answers,
                       IntegrationEventPublisher events, Clock clock) {
        this.votes = votes;
        this.questions = questions;
        this.answers = answers;
        this.events = events;
        this.clock = clock;
    }

    public record VoteResult(int myVote, int scoreDelta) {
    }

    private record Target(long authorId, long questionId) {
    }

    @Transactional
    public VoteResult cast(Actor actor, TargetType type, long targetId, int value) {
        Target target = resolve(type, targetId);
        if (actor.userId() == target.authorId()) {
            throw new BusinessRuleException("QA-1015", "不能给自己的内容投票");
        }

        Instant now = clock.instant();
        Optional<Vote> existing = votes.find(actor.userId(), type, targetId);
        int oldValue = existing.map(Vote::value).orElse(0);

        if (value == 0) {
            // 撤销：删除投票记录，得分回退旧值
            if (existing.isEmpty()) {
                return new VoteResult(0, 0);
            }
            votes.delete(existing.get());
            adjustScore(type, targetId, -oldValue);
            publishVoteEvent(type, targetId, target, actor.userId(), target.questionId(), oldValue, 0, now);
            return new VoteResult(0, -oldValue);
        }

        if (existing.isPresent()) {
            int previous = existing.get().change(value, now);
            if (previous == value) {
                // 幂等：重复投同一票不产生任何变更
                return new VoteResult(value, 0);
            }
            votes.save(existing.get());
            int delta = value - previous;
            adjustScore(type, targetId, delta);
            publishVoteEvent(type, targetId, target, actor.userId(), target.questionId(), previous, value, now);
            return new VoteResult(value, delta);
        }

        Vote vote = Vote.cast(actor.userId(), target.authorId(), type, targetId, value, now);
        votes.save(vote);
        adjustScore(type, targetId, value);
        publishVoteEvent(type, targetId, target, actor.userId(), target.questionId(), 0, value, now);
        return new VoteResult(value, value);
    }

    /** 解析被投票对象：返回作者 ID 与所属问题 ID（供事件消费者计算声誉）。 */
    private Target resolve(TargetType type, long targetId) {
        if (type == TargetType.QUESTION) {
            Question question = questions.findById(targetId)
                    .orElseThrow(() -> new NotFoundException("QA-4001", "问题不存在"));
            return new Target(question.authorId(), question.id());
        }
        Answer answer = answers.findById(targetId)
                .orElseThrow(() -> new NotFoundException("QA-4002", "回答不存在"));
        return new Target(answer.authorId(), answer.questionId());
    }

    /** 得分由仓储以原子 SQL 维护，避免读-改-写覆盖并发增量（ADR-008）。 */
    private void adjustScore(TargetType type, long targetId, int delta) {
        if (type == TargetType.QUESTION) {
            questions.adjustScore(targetId, delta);
        } else {
            answers.adjustScore(targetId, delta);
        }
    }

    private void publishVoteEvent(TargetType type, long targetId, Target target, long voterId, long questionId,
                                  int oldValue, int newValue, Instant now) {
        events.publish(new VoteCastEvent(EventIds.next(), now, type.name(), targetId, target.authorId(), voterId,
                questionId, oldValue, newValue));
    }
}
