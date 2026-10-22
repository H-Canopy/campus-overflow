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
import java.util.Optional;
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
        // 先查缓存
        Optional<PageResult<QuestionSummaryView>> cached = cache.get(courseId, page);
        if (cached.isPresent()) {
            return cached.get();
        }

        PageResult<QuestionRow> rows = readModel.listQuestions(courseId, page);

        // 批量查作者信息，避免 N+1
        Set<Long> authorIds = rows.items().stream().map(QuestionRow::authorId).collect(Collectors.toSet());
        Map<Long, UserSummary> users = identity.findUsers(authorIds);

        PageResult<QuestionSummaryView> result = rows.map(row -> {
            UserSummary author = users.get(row.authorId());
            String excerpt = renderer.toPlainText(row.body(), EXCERPT_LENGTH);
            boolean accepted = row.acceptedAnswerId() != null;
            return new QuestionSummaryView(
                    row.id(), row.title(), excerpt, row.tags(), row.courseId(),
                    AuthorView.of(row.authorId(), author),
                    row.score(), row.answerCount(), row.viewCount(), accepted, row.createdAt()
            );
        });

        cache.put(courseId, page, result);
        return result;
    }

    public QuestionDetailView detail(long questionId, Actor actorOrNull) {
        QuestionRow row = readModel.findQuestion(questionId)
                .orElseThrow(() -> new NotFoundException("QA-404", "问题不存在或已删除"));

        // 浏览计数 +1
        viewCounter.increment(questionId);

        // 查回答和评论
        List<AnswerRow> answerRows = readModel.findAnswers(questionId);
        List<CommentRow> commentRows = readModel.findComments(questionId);

        // 批量查所有作者（问题作者 + 回答作者 + 评论作者）
        Set<Long> authorIds = new HashSet<>();
        authorIds.add(row.authorId());
        answerRows.forEach(a -> authorIds.add(a.authorId()));
        commentRows.forEach(c -> authorIds.add(c.authorId()));
        Map<Long, UserSummary> users = identity.findUsers(authorIds);

        // 查课程信息
        CourseRefView course = row.courseId() != null
                ? identity.findCourse(row.courseId())
                    .map(c -> new CourseRefView(c.id(), c.code(), c.name()))
                    .orElse(null)
                : null;

        // 渲染正文
        String bodyHtml = renderer.toSafeHtml(row.body());

        // 查我的投票
        int myVote = 0;
        final Map<String, Integer> myVotes;
        if (actorOrNull != null) {
            List<Long> answerIds = answerRows.stream().map(AnswerRow::id).toList();
            myVotes = readModel.findMyVotes(actorOrNull.userId(), questionId, answerIds);
            myVote = myVotes.getOrDefault("QUESTION:" + questionId, 0);
        } else {
            myVotes = Map.of();
        }

        // 计算权限
        PermissionsView permissions = PermissionsView.NONE;
        if (actorOrNull != null) {
            boolean isAuthor = actorOrNull.is(row.authorId());
            boolean canManage = row.courseId() != null
                    && identity.canManageCourse(actorOrNull.userId(), row.courseId());
            boolean canEdit = isAuthor || canManage;
            boolean canDelete = isAuthor || canManage;
            boolean canAccept = isAuthor || canManage;
            permissions = new PermissionsView(canEdit, canDelete, canAccept, canManage);
        }

        // 组装回答列表（含回答下的评论）
        List<AnswerView> answers = answerRows.stream().map(a -> {
            UserSummary author = users.get(a.authorId());
            String answerHtml = renderer.toSafeHtml(a.body());
            boolean accepted = row.acceptedAnswerId() != null && row.acceptedAnswerId() == a.id();
            AuthorView endorsedBy = a.endorsedBy() != null
                    ? AuthorView.of(a.endorsedBy(), users.get(a.endorsedBy()))
                    : null;
            int answerMyVote = myVotes.getOrDefault("ANSWER:" + a.id(), 0);
            boolean canEdit = actorOrNull != null
                    && (actorOrNull.is(a.authorId()) || row.courseId() != null
                    && identity.canManageCourse(actorOrNull.userId(), row.courseId()));
            // 该回答下的评论
            List<CommentView> answerComments = commentRows.stream()
                    .filter(c -> "ANSWER".equals(c.targetType()) && c.targetId() == a.id())
                    .map(c -> new CommentView(c.id(), AuthorView.of(c.authorId(), users.get(c.authorId())),
                            c.parentId(), c.body(), c.createdAt()))
                    .toList();
            return new AnswerView(a.id(), a.body(), answerHtml, AuthorView.of(a.authorId(), author),
                    a.score(), accepted, endorsedBy, answerMyVote, canEdit, a.createdAt(), a.updatedAt(),
                    answerComments);
        }).toList();

        // 问题本身的顶级评论
        List<CommentView> topLevelComments = commentRows.stream()
                .filter(c -> "QUESTION".equals(c.targetType()) && c.targetId() == questionId)
                .map(c -> new CommentView(c.id(), AuthorView.of(c.authorId(), users.get(c.authorId())),
                        c.parentId(), c.body(), c.createdAt()))
                .toList();

        return new QuestionDetailView(
                row.id(), row.title(), row.body(), bodyHtml, row.tags(), course,
                AuthorView.of(row.authorId(), users.get(row.authorId())),
                row.score(), row.answerCount(), row.viewCount(), row.acceptedAnswerId(),
                myVote, row.createdAt(), row.updatedAt(), permissions, topLevelComments, answers
        );
    }

    public List<TagView> popularTags(int limit) {
        return readModel.popularTags(limit);
    }
}
