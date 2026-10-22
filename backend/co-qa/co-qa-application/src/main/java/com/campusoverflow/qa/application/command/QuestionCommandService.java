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
import java.time.Instant;
import java.util.List;
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
        Instant now = Instant.now(clock);
        Title title = new Title(cmd.title());
        MarkdownBody body = MarkdownBody.ofQuestion(cmd.body());
        Tags questionTags = Tags.of(cmd.tags());

        Question question = Question.post(actor.userId(), cmd.courseId(), title, body, questionTags, now);
        questions.save(question);

        tags.adjustUsage(questionTags.names(), +1);

        String plainText = renderer.toPlainText(cmd.body(), PLAIN_TEXT_FOR_INDEX);
        events.publish(new QuestionPostedEvent(EventIds.next(), now, question.id(), actor.userId(),
                cmd.courseId(), title.value(), plainText, questionTags.names()));

        listCache.invalidateAll();
        audit.record("question.post", actor.userId(), "Question", String.valueOf(question.id()),
                "title=" + title.value());
        return question.id();
    }

    @Transactional
    public void edit(Actor actor, long questionId, EditQuestionCommand cmd) {
        Question question = load(questionId);
        Instant now = Instant.now(clock);
        boolean manages = identity.canManageCourse(actor.userId(), question.courseId());

        Tags newTags = Tags.of(cmd.tags());
        question.edit(actor, manages, new Title(cmd.title()), MarkdownBody.ofQuestion(cmd.body()), newTags, now);
        questions.save(question);

        String plainText = renderer.toPlainText(cmd.body(), PLAIN_TEXT_FOR_INDEX);
        events.publish(new QuestionEditedEvent(EventIds.next(), now, questionId, question.title().value(),
                plainText, newTags.names()));

        listCache.invalidateAll();
        audit.record("question.edit", actor.userId(), "Question", String.valueOf(questionId), null);
    }

    @Transactional
    public void delete(Actor actor, long questionId) {
        Question question = load(questionId);
        Instant now = Instant.now(clock);
        boolean manages = identity.canManageCourse(actor.userId(), question.courseId());

        question.softDelete(actor, manages, now);
        questions.save(question);

        events.publish(new ContentDeletedEvent(EventIds.next(), now, "QUESTION", questionId, questionId));
        listCache.invalidateAll();
        audit.record("question.delete", actor.userId(), "Question", String.valueOf(questionId), null);
    }

    private Question load(long questionId) {
        return questions.findById(questionId)
                .orElseThrow(() -> new NotFoundException("QA-4001", "问题不存在"));
    }
}
