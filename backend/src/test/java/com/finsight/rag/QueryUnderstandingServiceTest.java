package com.finsight.rag;

import com.finsight.domain.model.AskQuestionCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryUnderstandingServiceTest {

    private final QueryUnderstandingService service = new QueryUnderstandingService();

    @Test
    void defaultsNullQuestionToFinancialQa() {
        assertThat(service.parse(new AskQuestionCommand(null, null, null)))
                .containsEntry("intent", "FINANCIAL_QA")
                .containsEntry("requiresMetrics", false);
    }
}
