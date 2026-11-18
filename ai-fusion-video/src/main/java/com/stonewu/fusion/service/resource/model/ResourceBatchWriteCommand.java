package com.stonewu.fusion.service.resource.model;

import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResourceBatchWriteCommand(
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String requestId,
        @NotNull CanvasValueType resourceType,
        @Positive long projectId,
        @NotNull @Size(min = 1, max = MAX_ENTRIES) List<@NotNull @Valid ResourceCommand> entries)
        implements ResourceCommand {
    public static final int MAX_ENTRIES = 20;

    public ResourceBatchWriteCommand {
        entries = List.copyOf(entries);
    }

    @Override
    public ResourceOperation operation() {
        return ResourceOperation.BATCH_WRITE;
    }
}
