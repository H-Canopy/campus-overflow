package com.campusoverflow.qa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusoverflow.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

class MarkdownBodyTest {

    @Test
    void trimsWhitespaceAndKeepsRawValue() {
        MarkdownBody body = MarkdownBody.ofQuestion("  插入节点后什么时候需要旋转？  ");
        assertThat(body.value()).isEqualTo("插入节点后什么时候需要旋转？");
    }

    @Test
    void blankBodyIsRejected() {
        assertThatThrownBy(() -> MarkdownBody.ofQuestion("   ")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("问题正文");
        assertThatThrownBy(() -> MarkdownBody.ofAnswer(null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void questionBodyHas10To2000Chars() {
        assertThatThrownBy(() -> MarkdownBody.ofQuestion("太短了")).isInstanceOf(BusinessRuleException.class);
        String tooLong = "啊".repeat(2001);
        assertThatThrownBy(() -> MarkdownBody.ofQuestion(tooLong)).isInstanceOf(BusinessRuleException.class);
        assertThat(MarkdownBody.ofQuestion("插入节点后什么时候需要旋转？").value()).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void answerBodyHas10To5000Chars() {
        assertThatThrownBy(() -> MarkdownBody.ofAnswer("太短了")).isInstanceOf(BusinessRuleException.class);
        String tooLong = "啊".repeat(5001);
        assertThatThrownBy(() -> MarkdownBody.ofAnswer(tooLong)).isInstanceOf(BusinessRuleException.class);
        assertThat(MarkdownBody.ofAnswer("看叔叔节点的颜色和插入位置。").value()).hasSizeGreaterThanOrEqualTo(10);
    }
}
