package com.stonewu.fusion.service.resource.model;

import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record ResourceBatchDeleteCommand(
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String requestId,
        @NotNull CanvasValueType resourceType,
        @Positive long projectId,
        @Positive Long parentId,
        @PositiveOrZero Integer expectedParentVersion,
        @NotNull @Size(min = 1, max = MAX_TARGETS) List<@NotNull @Valid Target> targets) implements ResourceCommand {
    public static final int MAX_TARGETS = 20;

    public record Target(@Positive long id, @NotNull @PositiveOrZero Integer expectedVersion) {
    }

    @Override
    public ResourceOperation operation() {
        return ResourceOperation.BATCH_DELETE;
    }
}
