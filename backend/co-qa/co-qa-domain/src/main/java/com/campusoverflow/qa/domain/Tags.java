package com.campusoverflow.qa.domain;

import com.campusoverflow.shared.domain.BusinessRuleException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 问题的标签集合：去重后 1–5 个。 */
public record Tags(List<TagName> values) {

    public static final int MAX = 5;

    public Tags {
        // 这里只去重，不校验数量：从库中重建数据时也必须能构造成功，
        // 否则历史问题（例如没有标签行的旧数据）一加载就被判成非法。
        // "1–5 个" 属于外部输入约束，由 of(...) 负责。
        Set<TagName> distinct = new LinkedHashSet<>(values);
        values = List.copyOf(distinct);
    }

    public static Tags of(Collection<String> raw) {
        if (raw == null) {
            throw new BusinessRuleException("QA-1005", "每个问题需要 1–" + MAX + " 个标签");
        }
        Tags tags = new Tags(raw.stream().map(TagName::new).toList());
        if (tags.values().isEmpty() || tags.values().size() > MAX) {
            throw new BusinessRuleException("QA-1005", "每个问题需要 1–" + MAX + " 个标签");
        }
        return tags;
    }

    /** 从持久化状态重建：跳过 1–5 个的数量校验（历史数据可能没有标签）。 */
    public static Tags reconstitute(Collection<String> raw) {
        return raw == null ? new Tags(List.of()) : new Tags(raw.stream().map(TagName::new).toList());
    }

    public List<String> names() {
        return values.stream().map(TagName::value).toList();
    }
}
