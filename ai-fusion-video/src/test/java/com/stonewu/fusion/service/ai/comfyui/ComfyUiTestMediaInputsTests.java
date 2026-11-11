package com.stonewu.fusion.service.ai.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflow;
import com.stonewu.fusion.entity.ai.ComfyUiWorkflowVersion;
import com.stonewu.fusion.service.ai.comfyui.test.ComfyUiTestInputs;
import com.stonewu.fusion.service.ai.comfyui.test.ComfyUiTestSnapshot;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.media.MediaInputTransport;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.media.MediaResourceQueries;
import com.stonewu.fusion.service.media.model.MediaException;
import com.stonewu.fusion.service.media.model.MediaFacts;
import com.stonewu.fusion.service.media.model.MediaKind;
import com.stonewu.fusion.service.media.model.MediaResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 保护草稿试运行的媒体权限、冻结版本与上传类别边界，防止视频/音频被按图片拒绝或错误传输。
 * 使用真实绑定解析和渲染，确保未提供可选素材时不读取媒体，也不保留占位上传输入。
 */
class ComfyUiTestMediaInputsTests {
    private static final String IMAGE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String VIDEO_ID = "00000000-0000-0000-0000-000000000002";
    private static final String AUDIO_ID = "00000000-0000-0000-0000-000000000003";
    private static final String IMAGE_DATA = "data:image/png;base64,aW1hZ2U=";
    private static final String VIDEO_DATA = "data:video/mp4;base64,dmlkZW8=";
    private static final String AUDIO_DATA = "data:audio/wav;base64,YXVkaW8=";

    private final CanvasActor actor = new CanvasActor(7L, 8L);
    private final ComfyUiWorkflow workflow = ComfyUiWorkflow.builder().modelType(3).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ComfyUiWorkflowDocumentService documents = new ComfyUiWorkflowDocumentService(json);
    private final ComfyUiWorkflowRenderer renderer = new ComfyUiWorkflowRenderer(json, documents);
    private final MediaResourceQueries media = mock(MediaResourceQueries.class);
    private final MediaInputTransport transport = mock(MediaInputTransport.class);
    private final ComfyUiTestInputs inputs = new ComfyUiTestInputs(documents, renderer, media, transport);

    @Test
    void freezesAllManagedMediaVersionsAndResolvesWithTheirExactKindsAndLimits() {
        String image = MediaReference.of(IMAGE_ID);
        String video = MediaReference.of(VIDEO_ID);
        String audio = MediaReference.of(AUDIO_ID);
        when(media.get(IMAGE_ID, actor)).thenReturn(resource(IMAGE_ID, MediaKind.IMAGE, 2));
        when(media.get(VIDEO_ID, actor)).thenReturn(resource(VIDEO_ID, MediaKind.VIDEO, 3));
        when(media.get(AUDIO_ID, actor)).thenReturn(resource(AUDIO_ID, MediaKind.AUDIO, 4));
        when(transport.dataUri(image, actor, MediaKind.IMAGE, 20L * 1024 * 1024)).thenReturn(IMAGE_DATA);
        when(transport.dataUri(video, actor, MediaKind.VIDEO, 200L * 1024 * 1024)).thenReturn(VIDEO_DATA);
        when(transport.dataUri(audio, actor, MediaKind.AUDIO, 50L * 1024 * 1024)).thenReturn(AUDIO_DATA);
        Map<String, Object> provided = Map.of("prompt", "test prompt", "referenceImages", List.of(image),
                "referenceVideos", List.of(video), "referenceAudios", List.of(audio));

        var snapshot = inputs.freeze(workflow, version(), provided, actor);

        assertThat(snapshot.mediaVersions()).containsExactlyInAnyOrderEntriesOf(
                Map.of(IMAGE_ID, 2, VIDEO_ID, 3, AUDIO_ID, 4));
        assertThat(snapshot.inputs()).isEqualTo(provided);
        verifyNoInteractions(transport);

        var resolved = inputs.resolve(snapshot, actor);

        assertThat(resolved).containsExactlyInAnyOrderEntriesOf(Map.of("prompt", "test prompt",
                "referenceImages", List.of(IMAGE_DATA), "referenceVideos", List.of(VIDEO_DATA),
                "referenceAudios", List.of(AUDIO_DATA)));
        assertThat(snapshot.inputs()).isEqualTo(provided);
        verify(transport).dataUri(image, actor, MediaKind.IMAGE, 20L * 1024 * 1024);
        verify(transport).dataUri(video, actor, MediaKind.VIDEO, 200L * 1024 * 1024);
        verify(transport).dataUri(audio, actor, MediaKind.AUDIO, 50L * 1024 * 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"referenceImages", "referenceVideos", "referenceAudios"})
    void refusesToFreezeUnauthorizedManagedMedia(String field) {
        var denied = new MediaException(403, "MEDIA_FORBIDDEN", "无权访问媒体");
        when(media.get(VIDEO_ID, actor)).thenThrow(denied);

        assertThatThrownBy(() -> inputs.freeze(workflow, version(),
                provided(field, List.of(MediaReference.of(VIDEO_ID))), actor)).isSameAs(denied);
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @CsvSource({"referenceImages,VIDEO", "referenceVideos,AUDIO", "referenceAudios,IMAGE"})
    void refusesToFreezeManagedMediaOfAnotherKind(String field, MediaKind actualKind) {
        when(media.get(VIDEO_ID, actor)).thenReturn(resource(VIDEO_ID, actualKind, 2));

        assertThatThrownBy(() -> inputs.freeze(workflow, version(),
                provided(field, List.of(MediaReference.of(VIDEO_ID))), actor))
                .isInstanceOf(MediaException.class).hasMessageContaining("媒体类型不符");
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @CsvSource({"referenceImages,IMAGE", "referenceVideos,VIDEO", "referenceAudios,AUDIO"})
    void rechecksMediaPermissionAfterTheSnapshotWasFrozen(String field, MediaKind kind) {
        var revoked = new MediaException(403, "MEDIA_FORBIDDEN", "媒体访问权限已撤销");
        when(media.get(VIDEO_ID, actor)).thenReturn(resource(VIDEO_ID, kind, 2)).thenThrow(revoked);
        var snapshot = inputs.freeze(workflow, version(),
                provided(field, List.of(MediaReference.of(VIDEO_ID))), actor);

        assertThatThrownBy(() -> inputs.resolve(snapshot, actor)).isSameAs(revoked);
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @CsvSource({"referenceImages,IMAGE", "referenceVideos,VIDEO", "referenceAudios,AUDIO"})
    void refusesChangedMediaVersionsBeforeTransport(String field, MediaKind kind) {
        when(media.get(VIDEO_ID, actor)).thenReturn(resource(VIDEO_ID, kind, 2), resource(VIDEO_ID, kind, 3));
        var snapshot = inputs.freeze(workflow, version(),
                provided(field, List.of(MediaReference.of(VIDEO_ID))), actor);

        assertThatThrownBy(() -> inputs.resolve(snapshot, actor))
                .isInstanceOfSatisfying(MediaException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("COMFY_TEST_MEDIA_CHANGED");
                });
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @CsvSource({"referenceVideos,VIDEO", "referenceAudios,AUDIO"})
    void refusesManagedReferencesWithoutAFrozenVersion(String field, MediaKind kind) {
        when(media.get(VIDEO_ID, actor)).thenReturn(resource(VIDEO_ID, kind, 2));
        var snapshot = new ComfyUiTestSnapshot(1, workflow, version(),
                provided(field, List.of(MediaReference.of(VIDEO_ID))), Map.of());

        assertThatThrownBy(() -> inputs.resolve(snapshot, actor))
                .isInstanceOfSatisfying(MediaException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("COMFY_TEST_MEDIA_CHANGED");
                });
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @CsvSource({"referenceImages,VIDEO", "referenceVideos,AUDIO", "referenceAudios,IMAGE"})
    void validatesPersistedSnapshotMediaKindsEvenWhenTheVersionMatches(String field, MediaKind actualKind) {
        when(media.get(VIDEO_ID, actor)).thenReturn(resource(VIDEO_ID, actualKind, 2));
        var snapshot = new ComfyUiTestSnapshot(1, workflow, version(),
                provided(field, List.of(MediaReference.of(VIDEO_ID))), Map.of(VIDEO_ID, 2));

        assertThatThrownBy(() -> inputs.resolve(snapshot, actor))
                .isInstanceOf(MediaException.class).hasMessageContaining("媒体类型不符");
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "referenceImages|data:image/png;base64,aW1hZ2U=",
            "referenceVideos|data:video/mp4;base64,dmlkZW8=",
            "referenceAudios|data:audio/wav;base64,YXVkaW8=",
            "referenceVideos|http://example.test/input.mp4",
            "referenceAudios|https://example.test/input.wav"})
    void preservesMatchingDataUrisAndHttpSourcesWithoutManagedMediaIo(String field, String source) {
        var snapshot = inputs.freeze(workflow, version(), provided(field, List.of(source)), actor);

        assertThat(snapshot.mediaVersions()).isEmpty();
        assertThat(inputs.resolve(snapshot, actor)).containsExactlyInAnyOrderEntriesOf(
                provided(field, List.of(source)));
        verifyNoInteractions(media, transport);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "referenceImages|data:video/mp4;base64,dmlkZW8=",
            "referenceImages|data:audio/wav;base64,YXVkaW8=",
            "referenceVideos|data:image/png;base64,aW1hZ2U=",
            "referenceVideos|data:audio/wav;base64,YXVkaW8=",
            "referenceAudios|data:image/png;base64,aW1hZ2U=",
            "referenceAudios|data:video/mp4;base64,dmlkZW8=",
            "referenceVideos|file:///tmp/input.mp4",
            "referenceAudios|unregistered-input.wav"})
    void refusesMismatchedOrUnsupportedSourcesAtBothSnapshotBoundaries(String field, String source) {
        var provided = provided(field, List.of(source));

        assertThatThrownBy(() -> inputs.freeze(workflow, version(), provided, actor))
                .isInstanceOf(MediaException.class).hasMessageContaining("对应类型的 Data URI");
        var snapshot = new ComfyUiTestSnapshot(1, workflow, version(), provided, Map.of());
        assertThatThrownBy(() -> inputs.resolve(snapshot, actor))
                .isInstanceOf(MediaException.class).hasMessageContaining("对应类型的 Data URI");
        verifyNoInteractions(media, transport);
    }

    @Test
    void missingOptionalMediaFieldsDoNotCreateUploadsOrReadMedia() {
        Map<String, Object> provided = Map.of("prompt", "test prompt");
        var snapshot = inputs.freeze(workflow, version(), provided, actor);
        var resolved = inputs.resolve(snapshot, actor);
        var rendered = renderer.render(3, snapshot.version(), resolved);

        assertThat(snapshot.mediaVersions()).isEmpty();
        assertThat(resolved).containsExactlyInAnyOrderEntriesOf(provided);
        assertThat(rendered.path("1").path("inputs").size()).isEqualTo(1);
        assertThat(rendered.path("1").path("inputs").path("prompt").asText()).isEqualTo("test prompt");
        for (String loader : List.of("20", "30", "31", "40")) {
            assertThat(rendered.has(loader)).isFalse();
        }
        verifyNoInteractions(media, transport);
    }

    @Test
    void refusesUnboundFieldsBeforeAccessingMedia() {
        assertThatThrownBy(() -> inputs.freeze(workflow, version(),
                Map.of("unboundMedia", List.of(MediaReference.of(VIDEO_ID))), actor))
                .isInstanceOf(MediaException.class).hasMessageContaining("未绑定字段");
        verifyNoInteractions(media, transport);
    }

    private Map<String, Object> provided(String field, Object value) {
        return Map.of("prompt", "test prompt", field, value);
    }

    private MediaResource resource(String id, MediaKind kind, int version) {
        String mimeType = switch (kind) {
            case IMAGE -> "image/png";
            case VIDEO -> "video/mp4";
            case AUDIO -> "audio/wav";
            case TEXT -> "text/plain";
            case DOCUMENT -> "application/pdf";
        };
        var facts = new MediaFacts(kind, mimeType, 5L, "hash", null, null, null, false);
        return new MediaResource(id, null, 1, actor.userId(), actor.userId(), "input", facts,
                "READY", version, null, null, null);
    }

    private ComfyUiWorkflowVersion version() {
        return ComfyUiWorkflowVersion.builder().apiWorkflowJson("""
                {
                  "1":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
                    "prompt":"placeholder","ref_images.ref_image_0":["20",0],
                    "ref_videos.ref_video_0":["30",0],"ref_videos.ref_video_1":["31",0],
                    "ref_audios.ref_audio_0":["40",0]}},
                  "20":{"class_type":"LoadImage","inputs":{"image":"placeholder.png"}},
                  "30":{"class_type":"VHS_LoadVideo","inputs":{"video":"placeholder-a.mp4"}},
                  "31":{"class_type":"VHS_LoadVideo","inputs":{"video":"placeholder-b.mp4"}},
                  "40":{"class_type":"VHS_LoadAudioUpload","inputs":{"audio":"placeholder.wav"}}
                }
                """).inputBindingsJson("""
                {
                  "prompt":[{"nodeId":"1","inputName":"prompt","valueType":"string"}],
                  "referenceImages":[{"nodeId":"1","inputName":"ref_images.ref_image_0",
                    "valueType":"uploaded_image","index":0,"optional":true}],
                  "referenceVideos":[{"nodeId":"1","inputName":"ref_videos.ref_video_0",
                    "valueType":"uploaded_video","index":0,"optional":true},
                    {"nodeId":"1","inputName":"ref_videos.ref_video_1",
                    "valueType":"uploaded_video","index":1,"optional":true}],
                  "referenceAudios":[{"nodeId":"1","inputName":"ref_audios.ref_audio_0",
                    "valueType":"uploaded_audio","index":0,"optional":true}]
                }
                """).build();
    }
}
