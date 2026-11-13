package com.stonewu.fusion.service.generation.review;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.service.generation.GenerationKind;

import java.util.List;

public final class GenerationContentReview {
    private GenerationContentReview() {
    }

    public enum Target {
        ASSET, SHOT_VIDEO, SHOT_FIRST_FRAME, SHOT_LAST_FRAME
    }

    public enum Verdict {
        MATCH, NEEDS_CHANGES, UNCERTAIN
    }

    public record Model(long id, String name, String fingerprint) {
    }

    public record Plan(Model model, GenerationKind kind, Target target, long projectId, Long storyboardId,
                       Long sceneId, List<Long> targetIds, String framePrompt) {
    }

    public record Selection(long modelId, Plan plan, Long targetId) {
        public static Selection manual(Long modelId) {
            return modelId == null ? null : new Selection(modelId, null, null);
        }
    }

    public record Criteria(Model model, Target target, Long targetId, ObjectNode context, String prompt,
                           boolean hasReferences) {
    }

    public record Finding(String issue, String evidence, String suggestion) {
    }

    public record Result(Verdict verdict, String summary, List<Finding> findings, List<String> limitations) {
    }

    public record View(String state, String modelName, Result result, String errorMessage) {
    }
}
