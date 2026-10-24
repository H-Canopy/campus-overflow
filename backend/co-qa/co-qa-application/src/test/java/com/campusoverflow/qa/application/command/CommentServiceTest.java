package com.campusoverflow.qa.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.qa.api.event.CommentPostedEvent;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryAnswers;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryQuestions;
import com.campusoverflow.qa.application.fake.QaTestFixtures.RecordingEvents;
import com.campusoverflow.qa.domain.Answer;
import com.campusoverflow.qa.domain.Comment;
import com.campusoverflow.qa.domain.CommentRepository;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.Tags;
import com.campusoverflow.qa.domain.TargetType;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CommentService 用例测试（Dev+QA 职责范围）。
 *
 * <p>本测试覆盖「应用层编排」：目标定位、父评论加载、事件发布与错误码。
 * 评论实体的领域规则（层级限制、挂载位置校验）由 {@code Comment.post} 负责，
 * 属② SM 的分工范围，其单元测试在 {@code VoteAndCommentTest} 中。</p>
 */
class CommentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryQuestions questions = new InMemoryQuestions();
    private final InMemoryAnswers answers = new InMemoryAnswers();
    private final InMemoryComments comments = new InMemoryComments();
    private final RecordingEvents events = new RecordingEvents();

    private CommentService service;

    private static final Actor MING = new Actor(1, "小明", Role.STUDENT);
    private static final Actor HONG = new Actor(2, "小红", Role.STUDENT);

    @BeforeEach
    void setUp() {
        service = new CommentService(comments, questions, answers, events, clock);

        questions.save(Question.reconstitute(100L, MING.userId(), null,
                new com.campusoverflow.qa.domain.Title("红黑树插入后为什么必须旋转？"),
                new MarkdownBody("变色为什么不够？希望有直观解释。"),
                Tags.of(List.of("数据结构")), null, 0, 1, 0, NOW, NOW, null, 0L));

        answers.save(Answer.reconstitute(200L, 100L, HONG.userId(),
                new MarkdownBody("关键在于黑高这个不变量。"), 0, null, NOW, NOW, null, 0L));
    }

    @Test
    @DisplayName("对回答发表评论：定位所属问题并发布事件")
    void postingOnAnswerResolvesQuestionAndPublishesEvent() {
        long id = service.post(MING, new PostCommentCommand(TargetType.ANSWER, 200L, null, "请问第二步为什么？"));

        assertThat(id).isPositive();
        List<CommentPostedEvent> published = events.ofType(CommentPostedEvent.class);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).questionId()).isEqualTo(100L);
        assertThat(published.get(0).authorId()).isEqualTo(MING.userId());
        assertThat(published.get(0).body()).isEqualTo("请问第二步为什么？");
    }

    @Test
    @DisplayName("对问题发表评论：questionId 即目标 ID")
    void postingOnQuestionUsesQuestionId() {
        service.post(HONG, new PostCommentCommand(TargetType.QUESTION, 100L, null, "同问"));

        CommentPostedEvent published = events.ofType(CommentPostedEvent.class).get(0);
        assertThat(published.questionId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("回复评论：父评论存在时可正常提交")
    void replyingToExistingCommentWorks() {
        comments.save(Comment.reconstitute(50L, TargetType.ANSWER, 200L, 100L, MING.userId(), null,
                "父评论", NOW));

        long id = service.post(HONG, new PostCommentCommand(TargetType.ANSWER, 200L, 50L, "子回复"));

        assertThat(id).isPositive();
        assertThat(comments.findById(id).orElseThrow().parentId()).isEqualTo(50L);
    }

    @Test
    @DisplayName("父评论不存在时抛出未找到异常")
    void replyingToMissingParentFails() {
        assertThatThrownBy(() -> service.post(HONG,
                new PostCommentCommand(TargetType.ANSWER, 200L, 999L, "子回复")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("目标不存在时抛出未找到异常")
    void postingOnMissingTargetFails() {
        assertThatThrownBy(() -> service.post(MING,
                new PostCommentCommand(TargetType.ANSWER, 999L, null, "内容")))
                .isInstanceOf(NotFoundException.class);
    }

    private static class InMemoryComments implements CommentRepository {
        private final Map<Long, Comment> store = new HashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public Optional<Comment> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Comment save(Comment comment) {
            if (comment.id() == null) {
                comment.assignId(sequence.incrementAndGet());
            }
            store.put(comment.id(), comment);
            return comment;
        }
    }
}
