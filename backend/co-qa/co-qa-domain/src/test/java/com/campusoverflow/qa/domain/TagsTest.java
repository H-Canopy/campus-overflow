package com.campusoverflow.qa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tags 两条构造路径的边界：外部输入（of）仍守 1–5，持久化重建（reconstitute）容忍历史空标签。
 */
class TagsTest {

    @Test
    void reconstituteAllowsEmptyForHistoricalData() {
        // 历史问题可能没有 qa_question_tag 行，重建时必须能构造成功
        assertThat(Tags.reconstitute(List.of()).names()).isEmpty();
    }

    @Test
    void reconstituteKeepsOrderAndDeduplicates() {
        assertThat(Tags.reconstitute(List.of("b", "a", "b")).names()).containsExactly("b", "a");
    }

    @Test
    void ofStillRejectsEmpty() {
        assertThatThrownBy(() -> Tags.of(List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("1–5");
    }

    @Test
    void ofStillRejectsMoreThanFive() {
        assertThatThrownBy(() -> Tags.of(List.of("a", "b", "c", "d", "e", "f")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("1–5");
    }

    @Test
    void ofAcceptsOneToFive() {
        assertThat(Tags.of(List.of("java")).names()).containsExactly("java");
        assertThat(Tags.of(List.of("a", "b", "c", "d", "e")).names()).hasSize(5);
    }
}
