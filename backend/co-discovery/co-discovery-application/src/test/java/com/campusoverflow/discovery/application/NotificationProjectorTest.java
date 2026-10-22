package com.campusoverflow.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusoverflow.discovery.domain.notification.Notification;
import com.campusoverflow.discovery.domain.notification.NotificationRepository;
import com.campusoverflow.discovery.domain.notification.NotificationType;
import com.campusoverflow.identity.api.CourseSummary;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.CommentPostedEvent;
import com.campusoverflow.reputation.api.event.BadgeAwardedEvent;
import com.campusoverflow.shared.page.PageRequest;
import com.campusoverflow.shared.page.PageResult;
import com.campusoverflow.shared.security.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 评审补测：NotificationProjector 的通知投递、自投递短路与幂等（Fake 仓储，无需容器）。 */
class NotificationProjectorTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private FakeNotificationRepository repo;
    private FakeIdentityApi identity;
    private NotificationProjector projector;

    @BeforeEach
    void setUp() {
        repo = new FakeNotificationRepository();
        identity = new FakeIdentityApi();
        projector = new NotificationProjector(repo, identity, null); // QaQueryApi 在当前用例未使用
    }

    @Test
    void answeringOwnQuestionDoesNotNotifySelf() {
        identity.add(new UserSummary(8, "提问者", Role.STUDENT, true, "计科"));
        projector.on(new AnswerSubmittedEvent("evt-1", NOW, 101, 7, 8, 8, "标题"));
        assertThat(repo.saved).isEmpty();
    }

    @Test
    void answeringOthersQuestionNotifiesQuestionAuthor() {
        identity.add(new UserSummary(8, "提问者", Role.STUDENT, true, "计科"));
        projector.on(new AnswerSubmittedEvent("evt-2", NOW, 101, 7, 9, 8, "标题"));
        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).recipientId()).isEqualTo(8);
        assertThat(repo.saved.get(0).type()).isEqualTo(NotificationType.ANSWER_RECEIVED);
    }

    @Test
    void acceptedNotifiesAnswerAuthor() {
        identity.add(new UserSummary(9, "回答者", Role.STUDENT, true, "计科"));
        projector.on(new AnswerAcceptedEvent("evt-3", NOW, 101, 7, 9, 8, 5, "标题"));
        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).recipientId()).isEqualTo(9);
        assertThat(repo.saved.get(0).type()).isEqualTo(NotificationType.ANSWER_ACCEPTED);
    }

    @Test
    void mentioningSelfInCommentIsSkipped() {
        identity.add(new UserSummary(5, "小明", Role.STUDENT, true, "计科"));
        projector.on(new CommentPostedEvent("evt-4", NOW, 1, 7, 5, "看看 @小明 这个"));
        assertThat(repo.saved).isEmpty();
    }

    @Test
    void mentioningOtherInCommentNotifies() {
        identity.add(new UserSummary(5, "小明", Role.STUDENT, true, "计科"));
        identity.add(new UserSummary(6, "小红", Role.STUDENT, true, "计科"));
        projector.on(new CommentPostedEvent("evt-5", NOW, 1, 7, 5, "看看 @小红 这个"));
        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).recipientId()).isEqualTo(6);
        assertThat(repo.saved.get(0).type()).isEqualTo(NotificationType.MENTIONED);
    }

    @Test
    void duplicateEventIsIdempotent() {
        identity.add(new UserSummary(8, "提问者", Role.STUDENT, true, "计科"));
        AnswerSubmittedEvent e = new AnswerSubmittedEvent("dup", NOW, 101, 7, 9, 8, "标题");
        projector.on(e);
        projector.on(e);
        assertThat(repo.saved).hasSize(1);
    }

    @Test
    void badgeNotificationHasNullLink() {
        identity.add(new UserSummary(9, "回答者", Role.STUDENT, true, "计科"));
        projector.on(new BadgeAwardedEvent("evt-6", NOW, 9, "B1", "金牌答主"));
        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).type()).isEqualTo(NotificationType.BADGE_AWARDED);
        assertThat(repo.saved.get(0).link()).isNull();
    }

    // ---- fakes ----

    private static final class FakeNotificationRepository implements NotificationRepository {
        final List<Notification> saved = new ArrayList<>();
        final Set<String> existing = new HashSet<>();

        @Override public boolean exists(String sourceEventId, long recipientId) {
            return existing.contains(sourceEventId + "|" + recipientId);
        }
        @Override public Notification save(Notification notification) {
            saved.add(notification);
            existing.add(notification.sourceEventId() + "|" + notification.recipientId());
            return notification;
        }
        @Override public Optional<Notification> findById(long id) { return Optional.empty(); }
        @Override public PageResult<Notification> findByRecipient(long recipientId, PageRequest page) { return null; }
        @Override public long countUnread(long recipientId) { return 0; }
        @Override public int markAllRead(long recipientId) { return 0; }
    }

    private static final class FakeIdentityApi implements IdentityApi {
        final Map<Long, UserSummary> byId = new HashMap<>();
        final Map<String, Long> byName = new HashMap<>();

        void add(UserSummary u) {
            byId.put(u.id(), u);
            byName.put(u.displayName(), u.id());
        }

        @Override public Optional<UserSummary> findUser(long userId) {
            return Optional.ofNullable(byId.get(userId));
        }
        @Override public Map<Long, UserSummary> findUsers(Collection<Long> userIds) {
            Map<Long, UserSummary> m = new HashMap<>();
            userIds.forEach(id -> {
                UserSummary u = byId.get(id);
                if (u != null) {
                    m.put(id, u);
                }
            });
            return m;
        }
        @Override public Optional<UserSummary> findByDisplayName(String displayName) {
            Long id = byName.get(displayName);
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }
        @Override public Optional<CourseSummary> findCourse(long courseId) { return Optional.empty(); }
        @Override public boolean canManageCourse(long userId, Long courseId) { return false; }
    }
}
