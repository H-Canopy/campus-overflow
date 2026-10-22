package com.campusoverflow.discovery.application;

import com.campusoverflow.discovery.domain.notification.MentionParser;
import com.campusoverflow.discovery.domain.notification.Notification;
import com.campusoverflow.discovery.domain.notification.NotificationRepository;
import com.campusoverflow.discovery.domain.notification.NotificationType;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.qa.api.QaQueryApi;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerEndorsedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.CommentPostedEvent;
import com.campusoverflow.reputation.api.event.BadgeAwardedEvent;
import com.campusoverflow.reputation.api.event.BountyAwardedEvent;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 通知中心：把上游事件转换为站内通知。幂等键 (sourceEventId, recipientId)。 */
@Component
public class NotificationProjector {

    private final NotificationRepository notifications;
    private final IdentityApi identity;
    private final QaQueryApi qa;

    public NotificationProjector(NotificationRepository notifications, IdentityApi identity, QaQueryApi qa) {
        this.notifications = notifications;
        this.identity = identity;
        this.qa = qa;
    }

    @EventListener
    @Transactional
    public void on(AnswerSubmittedEvent e) {
        // 提问者回答自己的问题：不给本人推送「你的问题收到新回答」。
        if (e.questionAuthorId() == e.answerAuthorId()) {
            return;
        }
        notify(e.questionAuthorId(), NotificationType.ANSWER_RECEIVED, "你的问题收到新回答",
                link(e.questionId()), e.eventId(), e.occurredAt());
    }

    @EventListener
    @Transactional
    public void on(AnswerAcceptedEvent e) {
        notify(e.answerAuthorId(), NotificationType.ANSWER_ACCEPTED, "你的回答被采纳了",
                link(e.questionId()), e.eventId(), e.occurredAt());
    }

    @EventListener
    @Transactional
    public void on(AnswerEndorsedEvent e) {
        notify(e.answerAuthorId(), NotificationType.ANSWER_ENDORSED, "你的回答获得教师认证",
                link(e.questionId()), e.eventId(), e.occurredAt());
    }

    @EventListener
    @Transactional
    public void on(CommentPostedEvent e) {
        String commenter = name(e.authorId());
        String who = commenter == null ? "有人" : commenter;
        // MentionParser.extract 返回 LinkedHashSet（已按 @昵称去重），不会对同一人重复查询或通知。
        MentionParser.extract(e.body()).forEach(mention ->
                identity.findByDisplayName(mention).ifPresent(user -> {
                    if (user.id() != e.authorId()) { // 评论 @ 到自己不推送
                        notify(user.id(), NotificationType.MENTIONED, who + " 在评论中提到了你",
                                link(e.questionId()), e.eventId(), e.occurredAt());
                    }
                }));
    }

    @EventListener
    @Transactional
    public void on(BountyAwardedEvent e) {
        notify(e.winnerId(), NotificationType.BOUNTY_AWARDED, "你获得了悬赏 " + e.points() + " 分",
                link(e.questionId()), e.eventId(), e.occurredAt());
    }

    @EventListener
    @Transactional
    public void on(BadgeAwardedEvent e) {
        notify(e.userId(), NotificationType.BADGE_AWARDED, "你获得了徽章「" + e.badgeName() + "」",
                null, e.eventId(), e.occurredAt());
    }

    private void notify(long recipientId, NotificationType type, String title, String link, String eventId,
                        Instant at) {
        if (notifications.exists(eventId, recipientId)) {
            return;
        }
        notifications.save(Notification.create(recipientId, type, title, link, eventId, at));
    }

    private String name(long userId) {
        return identity.findUser(userId).map(UserSummary::displayName).orElse(null);
    }

    private String link(long questionId) {
        return "/questions/" + questionId;
    }
}
