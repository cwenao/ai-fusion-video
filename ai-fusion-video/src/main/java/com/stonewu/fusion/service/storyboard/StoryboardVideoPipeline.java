package com.stonewu.fusion.service.storyboard;

public final class StoryboardVideoPipeline {
    public static final String ROOT = "storyboard_video_gen";
    public static final String EXECUTOR = "storyboard_video_executor";
    public static final String CHILD_TOOL = "generate_storyboard_video";

    private StoryboardVideoPipeline() {
    }

    public static boolean waitsDurably(String type, Long projectId) {
        return projectId != null && (ROOT.equals(type) || EXECUTOR.equals(type));
    }
}
