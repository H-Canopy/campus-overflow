package com.campusoverflow.qa.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.qa.api.QuestionRef;
import com.campusoverflow.qa.application.fake.QaTestFixtures.InMemoryQuestions;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.Tags;
import com.campusoverflow.shared.domain.NotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QaQueryApiImpl 测试（Dev+QA 职责范围）。
 *
 * <p>使用 {@code Question.reconstitute} 构造对象，绕开 {@code Question.post}（① PO 的分工），
 * 使本文件可在他人实现合入前独立验证。</p>
 */
class QaQueryApiImplTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");

    private final InMemoryQuestions questions = new InMemoryQuestions();
    private QaQueryApiImpl api;

    @BeforeEach
    void setUp() {
        api = new QaQueryApiImpl(questions);
    }

    @Test
    @DisplayName("查询问题：返回发布语言所需的最小信息")
    void findQuestionReturnsReference() {
        questions.save(question(100L, 1L, 500L, 200L, null));

        QuestionRef ref = api.findQuestion(100L).orElseThrow();

        assertThat(ref.id()).isEqualTo(100L);
        assertThat(ref.authorId()).isEqualTo(1L);
        assertThat(ref.courseId()).isEqualTo(500L);
        assertThat(ref.title()).isEqualTo("红黑树插入后为什么必须旋转？");
        assertThat(ref.acceptedAnswerId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("查询问题：问题不存在时返回空")
    void findQuestionReturnsEmptyForMissingQuestion() {
        assertThat(api.findQuestion(999L)).isEmpty();
    }

    @Test
    @DisplayName("查询问题：软删除的问题对其他上下文不可见")
    void findQuestionHidesDeletedQuestion() {
        questions.save(question(100L, 1L, 500L, null, NOW.plusSeconds(60)));

        assertThat(api.findQuestion(100L)).isEmpty();
    }

    private static Question question(long id, long authorId, Long courseId, Long acceptedAnswerId,
                                     Instant deletedAt) {
        return Question.reconstitute(id, authorId, courseId,
                new com.campusoverflow.qa.domain.Title("红黑树插入后为什么必须旋转？"),
                new MarkdownBody("变色为什么不够？希望有直观解释。"),
                Tags.of(List.of("数据结构")), acceptedAnswerId, 0, 0, 0, NOW, NOW, deletedAt, 0L);
    }
}
