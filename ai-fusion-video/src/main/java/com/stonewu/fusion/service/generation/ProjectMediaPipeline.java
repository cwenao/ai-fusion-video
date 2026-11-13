package com.stonewu.fusion.service.generation;

import java.util.Arrays;

public enum ProjectMediaPipeline {
    VIDEO("storyboard_video_gen", "storyboard_video_executor", "generate_storyboard_video", GenerationKind.VIDEO),
    FRAME("storyboard_frame_gen", "storyboard_frame_executor", "generate_storyboard_frame", GenerationKind.IMAGE),
    ASSET("asset_image_gen", "asset_image_executor", "generate_asset_image", GenerationKind.IMAGE);

    private final String root;
    private final String executor;
    private final String childTool;
    private final GenerationKind kind;

    ProjectMediaPipeline(String root, String executor, String childTool, GenerationKind kind) {
        this.root = root;
        this.executor = executor;
        this.childTool = childTool;
        this.kind = kind;
    }

    public static boolean waitsDurably(String type, Long projectId) {
        return projectId != null && Arrays.stream(values()).anyMatch(p -> p.root.equals(type) || p.executor.equals(type));
    }

    public static ProjectMediaPipeline forExecutor(String type) {
        return Arrays.stream(values()).filter(p -> p.executor.equals(type)).findFirst().orElse(null);
    }

    public static ProjectMediaPipeline forChildTool(String tool) {
        return Arrays.stream(values()).filter(p -> p.childTool.equals(tool)).findFirst().orElse(null);
    }

    public static boolean waitsForChild(String root, String tool) {
        var pipeline = forChildTool(tool);
        return pipeline != null && pipeline.root.equals(root);
    }

    public static boolean observesChild(String executor, String tool) {
        var pipeline = forChildTool(tool);
        return pipeline != null && pipeline.executor.equals(executor);
    }

    public GenerationKind kind() {
        return kind;
    }

    public boolean generationTool(String name) {
        return (kind == GenerationKind.VIDEO ? "generate_video" : "generate_image").equals(name);
    }
}
