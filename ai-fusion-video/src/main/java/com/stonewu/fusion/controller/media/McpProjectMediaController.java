package com.stonewu.fusion.controller.media;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.security.McpProjectSecurity;
import com.stonewu.fusion.service.mcp.McpProjectFailure;
import com.stonewu.fusion.service.mcp.McpProjectMedia;
import com.stonewu.fusion.service.media.MediaContentWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.project-mcp.enabled", havingValue = "true")
@RequestMapping(McpProjectMedia.ROOT)
public class McpProjectMediaController {
    private final McpProjectMedia media;
    private final MediaContentWriter writer;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CommonResult<ObjectNode>> upload(@PathVariable long projectId, @RequestParam String requestId,
                                                           @RequestParam("file") List<MultipartFile> files,
                                                           HttpServletRequest request) throws IOException {
        var data = media.upload(McpProjectSecurity.grant(request), projectId, requestId, files);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CommonResult.success(data));
    }

    @RequestMapping(path = "/{mediaResourceId}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void download(@PathVariable long projectId, @PathVariable String mediaResourceId,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        var grant = McpProjectSecurity.grant(request);
        var row = media.download(grant, projectId, mediaResourceId);
        writer.write(row.resource(), row.blobId(), true,
                () -> media.recheckDownload(grant, projectId, mediaResourceId, row.resource().version()), request, response);
    }

    @ExceptionHandler(McpProjectFailure.class)
    public ResponseEntity<CommonResult<MediaExceptionHandler.Failure>> failure(McpProjectFailure failure) {
        CommonResult<MediaExceptionHandler.Failure> body = CommonResult.error(failure.getCode(), failure.getMessage());
        body.setData(new MediaExceptionHandler.Failure(failure.getErrorCode()));
        return ResponseEntity.status(failure.getCode()).cacheControl(CacheControl.noStore()).body(body);
    }
}
