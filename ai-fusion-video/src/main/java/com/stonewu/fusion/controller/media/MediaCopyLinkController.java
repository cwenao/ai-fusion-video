package com.stonewu.fusion.controller.media;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.service.canvas.CanvasActorResolver;
import com.stonewu.fusion.service.media.MediaCopyLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class MediaCopyLinkController {
    private final CanvasActorResolver actors;
    private final MediaCopyLinks links;

    @PostMapping("/api/media-resources/{id}/copy-link")
    public ResponseEntity<CommonResult<MediaCopyLinks.Link>> issue(@PathVariable UUID id) {
        var link = links.issue(id.toString(), actors.current());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CommonResult.success(link));
    }
}
