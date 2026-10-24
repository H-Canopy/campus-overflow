package com.campusoverflow.qa.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryAnswers;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryQuestions;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryVotes;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingEvents;
import com.campusoverflow.qa.domain.Answer;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.TargetType;
import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VoteService 用例测试（Dev+QA 职责范围）。
 *
 * <p>本测试刻意使用 {@code reconstitute} 而非 {@code Question.post} / {@code Answer.submit}
 * 构造聚合对象：那两个工厂方法属于其他同学的分工范围（① PO / ② SM），在他们的实现合入前，
 * 本文件仍可独立验证投票用例的编排逻辑（得分增量、事件载荷、幂等性、权限判定）。
 * 待他人实现合入后，{@code AnswerAndVoteServiceTest} 会补上跨聚合的端到端断言。</p>
 */
class VoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryQuestions questions = new InMemoryQuestions();
    private final InMemoryAnswers answers = new InMemoryAnswers();
    private final InMemoryVotes votes = new InMemoryVotes();
    private final RecordingEvents events = new RecordingEvents();

    private VoteService voteService;

    private static final Actor MING = new Actor(1, "小明", Role.STUDENT);
    private static final Actor HONG = new Actor(2, "小红", Role.STUDENT);

    private long questionId;
    private long answerId;

    @BeforeEach
    void setUp() {
        voteService = new VoteService(votes, questions, answers, events, clock);

        Question question = Question.reconstitute(100L, MING.userId(), null,
                new com.campusoverflow.qa.domain.Title("红黑树插入后为什么必须旋转？"),
                new MarkdownBody("变色为什么不够？希望有直观解释。"),
                com.campusoverflow.qa.domain.Tags.of(List.of("数据结构")), null, 0, 1, 0, NOW, NOW, null, 0L);
        questions.save(question);
        questionId = question.id();

        Answer answer = Answer.reconstitute(200L, questionId, HONG.userId(),
                new MarkdownBody("关键在于黑高这个不变量。"), 0, null, NOW, NOW, null, 0L);
        answers.save(answer);
        answerId = answer.id();
    }

    @Test
    @DisplayName("首次投票：得分加 1，并发布携带 oldValue=0 的事件")
    void firstVoteAddsOneToScoreAndPublishesEvent() {
        VoteService.VoteResult result = voteService.cast(MING, TargetType.ANSWER, answerId, 1);

        assertThat(result.myVote()).isEqualTo(1);
        assertThat(result.scoreDelta()).isEqualTo(1);
        assertThat(answers.scoreDeltas).containsEntry(answerId, 1);

        List<VoteCastEvent> published = events.ofType(VoteCastEvent.class);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).oldValue()).isZero();
        assertThat(published.get(0).newValue()).isEqualTo(1);
        assertThat(published.get(0).targetAuthorId()).isEqualTo(HONG.userId());
        assertThat(published.get(0).questionId()).isEqualTo(questionId);
    }

    @Test
    @DisplayName("重复投同一票：幂等，不产生得分增量也不发事件")
    void votingSameValueTwiceIsIdempotent() {
        voteService.cast(MING, TargetType.ANSWER, answerId, 1);
        VoteService.VoteResult second = voteService.cast(MING, TargetType.ANSWER, answerId, 1);

        assertThat(second.myVote()).isEqualTo(1);
        assertThat(second.scoreDelta()).isZero();
        assertThat(answers.scoreDeltas).containsEntry(answerId, 1);
        assertThat(events.ofType(VoteCastEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("改投：增量按“新值 − 旧值”计算，事件同时携带新旧值")
    void changingVoteComputesDeltaAsNewMinusOld() {
        voteService.cast(MING, TargetType.ANSWER, answerId, 1);
        VoteService.VoteResult changed = voteService.cast(MING, TargetType.ANSWER, answerId, -1);

        assertThat(changed.myVote()).isEqualTo(-1);
        assertThat(changed.scoreDelta()).isEqualTo(-2);
        assertThat(answers.scoreDeltas).containsEntry(answerId, -1);

        List<VoteCastEvent> published = events.ofType(VoteCastEvent.class);
        assertThat(published).hasSize(2);
        assertThat(published.get(1).oldValue()).isEqualTo(1);
        assertThat(published.get(1).newValue()).isEqualTo(-1);
    }

    @Test
    @DisplayName("撤销投票：删除投票记录，得分回退，事件 newValue=0")
    void revokingVoteRemovesRecordAndRevertsScore() {
        voteService.cast(MING, TargetType.ANSWER, answerId, 1);
        VoteService.VoteResult revoked = voteService.cast(MING, TargetType.ANSWER, answerId, 0);

        assertThat(revoked.myVote()).isZero();
        assertThat(revoked.scoreDelta()).isEqualTo(-1);
        assertThat(answers.scoreDeltas).containsEntry(answerId, 0);
        assertThat(votes.find(MING.userId(), TargetType.ANSWER, answerId)).isEmpty();

        List<VoteCastEvent> published = events.ofType(VoteCastEvent.class);
        assertThat(published).hasSize(2);
        assertThat(published.get(1).oldValue()).isEqualTo(1);
        assertThat(published.get(1).newValue()).isZero();
    }

    @Test
    @DisplayName("不能给自己的内容投票")
    void cannotVoteOnOwnContent() {
        assertThatThrownBy(() -> voteService.cast(MING, TargetType.QUESTION, questionId, 1))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("自己");
    }

    @Test
    @DisplayName("投票对象不存在时抛出未找到异常")
    void votingOnMissingTargetFails() {
        assertThatThrownBy(() -> voteService.cast(MING, TargetType.ANSWER, 9999L, 1))
                .isInstanceOf(com.campusoverflow.shared.domain.NotFoundException.class);
    }
}
