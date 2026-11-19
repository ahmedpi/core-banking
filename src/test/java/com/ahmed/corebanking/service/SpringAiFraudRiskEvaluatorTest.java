package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.event.FraudAssessment;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class SpringAiFraudRiskEvaluatorTest {

    @Mock
    private OllamaChatModel chatModel;

    @Mock
    private RuleBasedFraudRiskEvaluator ruleBasedFraudRiskEvaluator;

    private SpringAiFraudRiskEvaluator springAiFraudRiskEvaluator;

    @BeforeEach
    void setup() {
        // Won't complain if a test throws an exception before calling getOptions()
        Mockito.lenient()
                .when(chatModel.getOptions())
                .thenReturn(OllamaChatOptions.builder().build());

        ChatClient.Builder chatCliientBuilder = ChatClient.builder(chatModel);
        springAiFraudRiskEvaluator = new SpringAiFraudRiskEvaluator(chatCliientBuilder, ruleBasedFraudRiskEvaluator);
    }

    @Test
    @DisplayName("Should return structured FraudAssessment from ChatClient without invoking rule-based fallback")
    void shouldReturnAiFraudAssessmentAndSkipFallbackWhenLlmSucceeds() {
        FraudAssessment assessment = new FraudAssessment(92, "HIGH", "AI detected anomaly");
        Mockito.when(chatModel.call(Mockito.any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"riskScore\":92,\"riskLevel\":\"HIGH\",\"reasoning\":\"AI detected anomaly\"}")))));

        TransferCompletedEvent eventPayload = createSampleEvent();
        List<Transaction> recentHourlyTransfers = Collections.emptyList();

        FraudAssessment result = springAiFraudRiskEvaluator.evaluate(eventPayload, recentHourlyTransfers);

        Assertions.assertThat(result).isEqualTo(assessment);
        Mockito.verifyNoInteractions(ruleBasedFraudRiskEvaluator);
    }

    @Test
    @DisplayName("Should fall back to RuleBasedFraudRiskEvaluator when ChatClient throws an exception")
    void shouldFallbackToRuleBasedEvaluatorWhenChatClientFails() {
        Mockito.when(chatModel.call(Mockito.any(Prompt.class)))
                .thenThrow(new RuntimeException("Ollama connection timeout"));

        TransferCompletedEvent eventPayload = createSampleEvent();
        List<Transaction> recentHourlyTransfers = Collections.emptyList();
        FraudAssessment fallbackAssessment = new FraudAssessment(92, "HIGH", "AI detected anomaly");

        Mockito.when(ruleBasedFraudRiskEvaluator.evaluate(eventPayload, recentHourlyTransfers)).thenReturn(fallbackAssessment);

        FraudAssessment result = springAiFraudRiskEvaluator.evaluate(eventPayload, recentHourlyTransfers);

        Assertions.assertThat(result).isEqualTo(fallbackAssessment);
        Mockito.verify(ruleBasedFraudRiskEvaluator, Mockito.times(1)).evaluate(eventPayload, recentHourlyTransfers);
    }

    private TransferCompletedEvent createSampleEvent() {
        return new TransferCompletedEvent(
                UUID.randomUUID(),
                "ACC123",
                "ACC456",
                new BigDecimal("6000.00"),
                Instant.now()
        );
    }
}
