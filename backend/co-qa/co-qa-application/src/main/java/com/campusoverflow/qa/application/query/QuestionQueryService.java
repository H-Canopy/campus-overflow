package com.campusoverflow.qa.application.query;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.qa.application.port.ContentRenderer;
import com.campusoverflow.qa.application.port.QaReadModel;
import com.campusoverflow.qa.application.port.QuestionListCache;
import com.campusoverflow.qa.application.port.ViewCounter;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Actor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 问答查询用例（读路径）。 */
@Service
@Transactional(readOnly = true)
public class QuestionQueryService {

    private static final int EXCERPT_LENGTH = 160;

    private final QaReadModel readModel;
    private final IdentityApi identity;
    private final ContentRenderer renderer;
    private final ViewCounter viewCounter;
    private final QuestionListCache cache;

    public QuestionQueryService(QaReadModel readModel, IdentityApi identity, ContentRenderer renderer,
                                ViewCounter viewCounter, QuestionListCache cache) {
        this.readModel = readModel;
        this.identity = identity;
        this.renderer = renderer;
        this.viewCounter = viewCounter;
        this.cache = cache;
    }

    public PageResult<QuestionSummaryView> list(Long courseId, PageRequest page) {
        var cached = cache.get(courseId, page);
        if (cached.isPresent()) {
            return cached.get();
        }
        PageResult<QuestionRow> rows = readModel.listQuestions(courseId, page);
        List<Long> authorIds = rows.items().stream().map(QuestionRow::authorId).distinct().toList();
        var authors = identity.findUsers(authorIds);

        PageResult<QuestionSummaryView> result = rows.map(row -> new QuestionSummaryView(
                row.id(), row.title(), renderer.toPlainText(row.body(), EXCERPT_LENGTH), row.tags(),
                row.courseId(), AuthorView.of(row.authorId(), authors.get(row.authorId())),
                row.score(), row.answerCount(), row.viewCount(), row.acceptedAnswerId() != null, row.createdAt()));
        cache.put(courseId, page, result);
        return result;
    }

    public QuestionDetailView detail(long questionId, Actor actorOrNull) {
        QuestionRow row = readModel.findQuestion(questionId)
                .orElseThrow(() -> new NotFoundException("QA-4001", "问题不存在"));
        viewCounter.increment(questionId);

        List<AnswerRow> answers = readModel.findAnswers(questionId);
        List<CommentRow> comments = readModel.findComments(questionId);

        List<Long> authorIds = new java.util.ArrayList<>();
        authorIds.add(row.authorId());
        answers.forEach(a -> authorIds.add(a.authorId()));
        comments.forEach(c -> authorIds.add(c.authorId()));
        var authors = identity.findUsers(authorIds.stream().distinct().toList());

        Map<String, Integer> myVotes = actorOrNull == null ? Map.of()
                : readModel.findMyVotes(actorOrNull.userId(), questionId,
                        answers.stream().map(AnswerRow::id).toList());

        long myVote = myVotes.getOrDefault("QUESTION:" + questionId, 0);

        List<AnswerView> answerViews = answers.stream().map(a -> {
            boolean accepted = row.acceptedAnswerId() != null && row.acceptedAnswerId() == a.id();
            int vote = myVotes.getOrDefault("ANSWER:" + a.id(), 0);
            return new AnswerView(a.id(), a.body(), renderer.toSafeHtml(a.body()),
                    AuthorView.of(a.authorId(), authors.get(a.authorId())), a.score(), accepted,
                    AuthorView.of(a.authorId(), authors.get(a.authorId())), vote, false,
                    a.createdAt(), a.updatedAt(), List.of());
        }).toList();

        List<CommentView> commentViews = comments.stream().map(c -> new CommentView(
                c.id(), AuthorView.of(c.authorId(), authors.get(c.authorId())), c.parentId(), c.body(), c.createdAt()))
                .toList();

        PermissionsView permissions = actorOrNull == null ? PermissionsView.NONE
                : new PermissionsView(row.authorId() == actorOrNull.userId(),
                row.authorId() == actorOrNull.userId() || identity.canManageCourse(actorOrNull.userId(), row.courseId()),
                row.authorId() == actorOrNull.userId() || identity.canManageCourse(actorOrNull.userId(), row.courseId()),
                identity.canManageCourse(actorOrNull.userId(), row.courseId()));

        return new QuestionDetailView(row.id(), row.title(), row.body(), renderer.toSafeHtml(row.body()), row.tags(),
                new CourseRefView(row.courseId() == null ? 0 : row.courseId(), "", ""),
                AuthorView.of(row.authorId(), authors.get(row.authorId())), row.score(), row.answerCount(),
                row.viewCount(), row.acceptedAnswerId(), (int) myVote, row.createdAt(), row.updatedAt(),
                permissions, commentViews, answerViews);
    }

    public List<TagView> popularTags(int limit) {
        return readModel.popularTags(limit);
    }
}
