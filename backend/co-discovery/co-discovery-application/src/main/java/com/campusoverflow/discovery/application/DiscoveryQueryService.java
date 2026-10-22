package com.campusoverflow.discovery.application;

import com.campusoverflow.discovery.domain.search.RelatedQuestionPolicy;
import com.campusoverflow.discovery.domain.search.SearchHit;
import com.campusoverflow.discovery.domain.search.SearchIndex;
import com.campusoverflow.discovery.domain.search.SearchPort;
import com.campusoverflow.discovery.domain.search.SearchQuery;
import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.shared.page.PageResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用例：搜索与筛选、相关问题推荐。 */
@Service
@Transactional(readOnly = true)
public class DiscoveryQueryService {

    private final SearchPort search;
    private final SearchIndex index;
    private final IdentityApi identity;

    public DiscoveryQueryService(SearchPort search, SearchIndex index, IdentityApi identity) {
        this.search = search;
        this.index = index;
        this.identity = identity;
    }

    public PageResult<SearchResultView> search(SearchQuery query) {
        PageResult<SearchHit> hits = search.search(query);

        // 批量查作者
        List<Long> authorIds = hits.items().stream().map(SearchHit::authorId).toList();
        Map<Long, UserSummary> users = identity.findUsers(authorIds);

        return hits.map(hit -> {
            UserSummary author = users.get(hit.authorId());
            return new SearchResultView(
                    hit.questionId(), hit.title(), hit.snippet(), hit.tags(), hit.courseId(),
                    new SearchResultView.Author(
                            hit.authorId(),
                            author != null ? author.displayName() : "已注销用户",
                            author != null ? author.role().name() : "STUDENT",
                            author != null && author.verified()
                    ),
                    hit.score(), hit.answerCount(), hit.accepted(), hit.bountyOpen(), hit.createdAt()
            );
        });
    }

    public List<RelatedQuestionView> related(long questionId, int limit) {
        List<String> myTags = index.findTags(questionId);
        if (myTags.isEmpty()) {
            return List.of();
        }

        // 先取多一些候选，再排序
        List<SearchIndex.RelatedCandidate> candidates =
                index.findCandidatesSharingTags(questionId, myTags, limit * 3);

        List<SearchIndex.RelatedCandidate> ranked =
                RelatedQuestionPolicy.rank(candidates, myTags.size(), limit);

        return ranked.stream()
                .map(c -> new RelatedQuestionView(
                        c.questionId(), c.title(), c.answerCount(), c.accepted(),
                        RelatedQuestionPolicy.jaccard(c.sharedTags(), myTags.size(), c.tagCount())
                ))
                .toList();
    }
}
