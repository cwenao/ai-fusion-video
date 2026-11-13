package com.stonewu.fusion.service.generation.review;

import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.media.MediaContentReader;
import com.stonewu.fusion.service.media.MediaInputTransport;
import com.stonewu.fusion.service.media.MediaIntakeService;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.media.job.MediaProcessRunner;
import com.stonewu.fusion.service.media.model.MediaFacts;
import com.stonewu.fusion.service.media.model.MediaKind;
import io.agentscope.core.message.Base64Source;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
public class GenerationReviewMedia {
    static final long INLINE_LIMIT = 10L * 1024 * 1024;

    private final MediaInputTransport transport;
    private final MediaContentReader reader;
    private final MediaProcessRunner ffmpeg;

    @Value("${video.compose.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    record Input(Base64Source source, boolean resized) {
    }

    Input prepare(String id, CanvasActor actor, MediaFacts facts, Runnable active) throws IOException {
        if (facts.byteSize() <= INLINE_LIMIT) {
            String data = transport.dataUri(MediaReference.of(id), actor, facts.kind(), INLINE_LIMIT);
            return new Input(Base64Source.builder().mediaType(facts.mimeType())
                    .data(data.substring(data.indexOf(',') + 1)).build(), false);
        }
        if (facts.kind() != MediaKind.VIDEO) {
            throw new GenerationReviewFailure("图片超过 AI 审查允许的 10 MiB");
        }
        return encodeVideo(id, actor, facts, active);
    }

    private Input encodeVideo(String id, CanvasActor actor, MediaFacts facts, Runnable active) throws IOException {
        if (facts.durationMs() == null || facts.durationMs() <= 0) {
            throw new GenerationReviewFailure("无法读取视频时长，不能准备审查素材");
        }
        long videoKbps = Math.min(1800, (INLINE_LIMIT - 512 * 1024) * 8 / facts.durationMs() - 96);
        if (videoKbps < 180) {
            throw new GenerationReviewFailure("视频过长，无法在审查大小限制内保留足够的画面细节");
        }
        Path work = Files.createTempDirectory("afv-content-review-");
        try {
            Path input = work.resolve("original.video");
            Path output = work.resolve("review.mp4");
            reader.copy(id, actor, input, MediaIntakeService.MAX_RESULT_BYTES, active);
            ffmpeg.run(List.of(ffmpegPath, "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe",
                    "-i", input.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-vf",
                    "scale=w='min(iw,960)':h='min(ih,960)':force_original_aspect_ratio=decrease:force_divisible_by=2",
                    "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-b:v", videoKbps + "k",
                    "-maxrate", videoKbps + "k", "-bufsize", videoKbps + "k",
                    "-c:a", "aac", "-b:a", "80k", "-map_metadata", "-1", output.toString()), work, output, active);
            if (Files.size(output) > INLINE_LIMIT) {
                throw new GenerationReviewFailure("完整视频转码后仍超过 AI 审查允许的 10 MiB");
            }
            return new Input(Base64Source.builder().mediaType("video/mp4")
                    .data(Base64.getEncoder().encodeToString(Files.readAllBytes(output))).build(), true);
        } finally {
            FileSystemUtils.deleteRecursively(work);
        }
    }
}
