package com.stonewu.fusion.service.storyboard.dto;

import java.time.Instant;

public record StoryboardPromptVersion(int version, String prompt, Instant replacedAt) {
}
