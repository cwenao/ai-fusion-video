package com.stonewu.fusion.service.generation.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.stonewu.fusion.service.generation.review.GenerationContentReview.*;

@Component
@RequiredArgsConstructor
public class GenerationReviewResponse {
    private static final int MAX_LENGTH = 16000;
    private static final Set<String> FINISHED = Set.of("stop", "end_turn", "stop_sequence");

    private final ObjectMapper mapper;

    Result read(Collected response, List<String> systemLimitations) {
        if (response == null || response.finish == null || !FINISHED.contains(
                response.finish.toLowerCase(Locale.ROOT))) {
            throw new GenerationReviewFailure("模型没有返回完整的审查结果");
        }
        String text = response.text.toString().strip();
        if (text.startsWith("```json\n") && text.endsWith("\n```")) {
            text = text.substring(8, text.length() - 4).strip();
        }
        try {
            JsonNode report = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (report == null || !report.isObject()) {
                throw invalid();
            }
            Verdict verdict = Verdict.valueOf(requiredText(report.path("verdict"), 32));
            String summary = requiredText(report.path("summary"), 1200);
            var items = report.path("findings");
            var limits = report.path("limitations");
            if (!items.isArray() || items.size() > 8 || !limits.isArray() || limits.size() > 8
                    || (verdict == Verdict.NEEDS_CHANGES && items.isEmpty())
                    || (verdict == Verdict.UNCERTAIN && limits.isEmpty())) {
                throw invalid();
            }
            var findings = new ArrayList<Finding>();
            for (var item : items) {
                findings.add(new Finding(requiredText(item.path("issue"), 400),
                        requiredText(item.path("evidence"), 600), requiredText(item.path("suggestion"), 600)));
            }
            var limitations = new ArrayList<String>(systemLimitations);
            for (var item : limits) {
                limitations.add(requiredText(item, 400));
            }
            return new Result(verdict, summary, List.copyOf(findings), limitations.stream().distinct().toList());
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static String requiredText(JsonNode node, int max) {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max) {
            throw invalid();
        }
        return node.textValue().strip();
    }

    private static GenerationReviewFailure invalid() {
        return new GenerationReviewFailure("模型返回的审查格式无效，未记录为有效结论");
    }

    static final class Collected {
        private final StringBuilder text = new StringBuilder();
        private String finish;

        Collected add(ChatResponse response) {
            for (var block : response.getContent()) {
                if (block instanceof TextBlock value && value.getText() != null) {
                    if (value.getText().length() > MAX_LENGTH - text.length()) {
                        throw invalid();
                    }
                    text.append(value.getText());
                } else if (block instanceof ToolUseBlock) {
                    throw invalid();
                }
            }
            if (response.getFinishReason() != null) {
                finish = response.getFinishReason();
            }
            return this;
        }
    }
}
