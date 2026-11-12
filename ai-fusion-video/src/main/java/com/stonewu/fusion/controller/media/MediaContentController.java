package com.stonewu.fusion.controller.media;

import com.stonewu.fusion.service.media.MediaAccessTickets;
import com.stonewu.fusion.service.media.MediaContentWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 此入口不使用登录 Cookie；每次都验证短时凭据及其持有者的当前权限。
 */
@RestController
@RequiredArgsConstructor
public class MediaContentController {
    private final MediaAccessTickets tickets;
    private final MediaContentWriter contentWriter;

    @RequestMapping(value = "/api/media-content/{id}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void content(@PathVariable String id, @RequestParam String ticket,
                        HttpServletRequest request, HttpServletResponse response) throws IOException {
        var grant = tickets.authorize(id, ticket);
        contentWriter.write(grant.resource(), grant.blobId(), grant.download(),
                () -> tickets.authorize(id, ticket), request, response);
    }
}
