package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ReferenceImageTransportService;
import com.stonewu.fusion.service.generation.job.GenerationInputVersions;
import com.stonewu.fusion.service.generation.job.GenerationProviderInputs;
import com.stonewu.fusion.service.generation.job.GenerationSubmission;
import com.stonewu.fusion.service.generation.job.RemoteGenerationJob;
import com.stonewu.fusion.service.media.MediaInputTransport;
import com.stonewu.fusion.service.media.model.MediaKind;
import com.stonewu.fusion.service.resource.model.ResourceCommandContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Managed ComfyUI AV references must transfer bytes, without needing public TLS URLs; other providers stay URL-based. */
class ComfyUiProductionMediaTransportTests {
    @ParameterizedTest
    @ValueSource(strings = {"comfyui", "other-provider"})
    void routesBothOrdinaryAndCanvasReferencesUsingProviderTransport(String platform) {
        var json = new ObjectMapper();
        var capabilities = mock(GenerationModelCapabilityService.class);
        var references = mock(ReferenceImageTransportService.class);
        var transport = mock(MediaInputTransport.class);
        var versions = mock(GenerationInputVersions.class);
        var service = new GenerationProviderInputs(new CanvasJson(json), capabilities, references, transport, versions);
        var actor = new CanvasActor(31L, null);
        var submission = mock(GenerationSubmission.class);
        var model = AiModel.builder().build();
        when(submission.context()).thenReturn(ResourceCommandContext.user(actor, null));
        when(submission.model()).thenReturn(model);
        when(capabilities.getMergedModelConfig(model)).thenReturn(new JSONObject());
        var job = mock(RemoteGenerationJob.class);
        var claim = new RemoteGenerationJob.Claim("job", "worker", 1);
        when(job.claim()).thenReturn(claim);
        var raw = json.createObjectNode();
        raw.put("referenceVideoUrls", "videos").put("referenceAudioUrls", "audios");
        var frozen = raw.putObject("workflowInputs");
        frozen.putArray("referenceVideos").add("media://11111111-1111-1111-1111-111111111111");
        frozen.putArray("referenceAudios").add("media://22222222-2222-2222-2222-222222222222");
        when(job.input()).thenReturn(raw);
        when(references.parseJsonInputs("videos", "referenceVideoUrls"))
                .thenReturn(List.of("media://11111111-1111-1111-1111-111111111111"));
        when(references.parseJsonInputs("audios", "referenceAudioUrls"))
                .thenReturn(List.of("media://22222222-2222-2222-2222-222222222222"));
        when(references.parseJsonInputs(null, "referenceImageUrls")).thenReturn(List.of());
        if ("comfyui".equals(platform)) {
            when(transport.dataUri(any(), eq(actor), eq(MediaKind.VIDEO), eq(200L * 1024 * 1024)))
                    .thenReturn("data:video/mp4;base64,dmlkZW8=");
            when(transport.dataUri(any(), eq(actor), eq(MediaKind.AUDIO), eq(50L * 1024 * 1024)))
                    .thenReturn("data:audio/wav;base64,YXVkaW8=");
        } else {
            when(transport.generationUrl(any(), eq(actor), any(), eq(claim)))
                    .thenReturn("https://media.example/ticket");
        }
        var task = service.video(job, submission, ApiConfig.builder().platform(platform).build(), true);
        if ("comfyui".equals(platform)) {
            assertThat(task.getReferenceVideoUrls()).contains("data:video/mp4");
            assertThat(task.getReferenceAudioUrls()).contains("data:audio/wav");
            assertThat(task.getWorkflowInputs().path("referenceVideos").get(0).asText()).startsWith("data:video/");
            assertThat(task.getWorkflowInputs().path("referenceAudios").get(0).asText()).startsWith("data:audio/");
            verify(transport, never()).generationUrl(any(), any(), any(), any());
        } else {
            assertThat(task.getReferenceVideoUrls()).contains("https://media.example/ticket");
            assertThat(task.getReferenceAudioUrls()).contains("https://media.example/ticket");
            verify(transport, never()).dataUri(any(), any(), any(), anyLong());
        }
        assertThat(raw.path("workflowInputs").path("referenceVideos").get(0).asText()).startsWith("media://");
    }
}
