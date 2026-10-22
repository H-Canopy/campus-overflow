package com.campusoverflow.qa.application.command;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.qa.api.event.ContentDeletedEvent;
import com.campusoverflow.qa.api.event.QuestionEditedEvent;
import com.campusoverflow.qa.api.event.QuestionPostedEvent;
import com.campusoverflow.qa.application.port.ContentRenderer;
import com.campusoverflow.qa.application.port.QuestionListCache;
import com.campusoverflow.qa.domain.MarkdownBody;
import com.campusoverflow.qa.domain.Question;
import com.campusoverflow.qa.domain.QuestionRepository;
import com.campusoverflow.qa.domain.TagRepository;
import com.campusoverflow.qa.domain.Tags;
import com.campusoverflow.qa.domain.Title;
import com.campusoverflow.shared.audit.AuditTrail;
import com.campusoverflow.shared.domain.NotFoundException;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import com.campusoverflow.shared.security.Actor;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用例：发布 / 编辑 / 删除问题。 */
@Service
public class QuestionCommandService {

    static final int PLAIN_TEXT_FOR_INDEX = 2000;

    private final QuestionRepository questions;
    private final TagRepository tags;
    private final IdentityApi identity;
    private final IntegrationEventPublisher events;
    private final ContentRenderer renderer;
    private final QuestionListCache listCache;
    private final AuditTrail audit;
    private final Clock clock;

    public QuestionCommandService(QuestionRepository questions, TagRepository tags, IdentityApi identity,
                                  IntegrationEventPublisher events, ContentRenderer renderer,
                                  QuestionListCache listCache, AuditTrail audit, Clock clock) {
        this.questions = questions;
        this.tags = tags;
        this.identity = identity;
        this.events = events;
        this.renderer = renderer;
        this.listCache = listCache;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public long post(Actor actor, PostQuestionCommand cmd) {
        Title title = new Title(cmd.title());
        MarkdownBody body = MarkdownBody.ofQuestion(cmd.body());
        Tags questionTags = Tags.of(cmd.tags());

        Question question = Question.post(actor.userId(), cmd.courseId(), title, body, questionTags, clock.instant());
        questions.save(question);

        this.tags.adjustUsage(cmd.tags(), +1);

        String plainText = renderer.toPlainText(cmd.body(), PLAIN_TEXT_FOR_INDEX);
        events.publish(new QuestionPostedEvent(EventIds.next(), clock.instant(),
                question.id(), actor.userId(), cmd.courseId(), cmd.title(), plainText, questionTags.names()));

        listCache.invalidateAll();
        return question.id();
    }

    @Transactional
    public void edit(Actor actor, long questionId, EditQuestionCommand cmd) {
        Question question = load(questionId);
        boolean canManage = identity.canManageCourse(actor.userId(), question.courseId());

        // 先记录旧标签，后面要减计数
        List<String> oldTagNames = question.tags().names();

        Title newTitle = new Title(cmd.title());
        MarkdownBody newBody = MarkdownBody.ofQuestion(cmd.body());
        Tags newTags = Tags.of(cmd.tags());

        question.edit(actor, canManage, newTitle, newBody, newTags, clock.instant());
        questions.save(question);

        // 更新标签计数：旧的 -1，新的 +1
        tags.adjustUsage(oldTagNames, -1);
        tags.adjustUsage(cmd.tags(), +1);

        String plainText = renderer.toPlainText(cmd.body(), PLAIN_TEXT_FOR_INDEX);
        events.publish(new QuestionEditedEvent(EventIds.next(), clock.instant(),
                questionId, cmd.title(), plainText, newTags.names()));

        listCache.invalidateAll();
    }

    @Transactional
    public void delete(Actor actor, long questionId) {
        Question question = load(questionId);
        boolean canManage = identity.canManageCourse(actor.userId(), question.courseId());

        question.softDelete(actor, canManage, clock.instant());
        questions.save(question);

        // 删除问题时标签计数 -1
        tags.adjustUsage(question.tags().names(), -1);

        events.publish(new ContentDeletedEvent(EventIds.next(), clock.instant(),
                "QUESTION", questionId, questionId));

        audit.record("question.delete", actor.userId(), "QUESTION", String.valueOf(questionId), null);
        listCache.invalidateAll();
    }

    private Question load(long questionId) {
        return questions.findById(questionId)
                .orElseThrow(() -> new NotFoundException("QA-404", "问题不存在或已删除"));
    }
}
