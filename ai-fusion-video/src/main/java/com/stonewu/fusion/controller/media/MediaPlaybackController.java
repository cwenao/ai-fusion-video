package com.stonewu.fusion.controller.media;

import com.stonewu.fusion.service.media.MediaAccessTickets;
import com.stonewu.fusion.service.media.TosMediaPlayback;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MediaPlaybackController {

    private final MediaAccessTickets tickets;
    private final TosMediaPlayback playback;

    @GetMapping("/api/media-content/{id}/playback")
    public ResponseEntity<TosMediaPlayback.Grant> playback(@PathVariable String id, @RequestParam String ticket) {
        var grant = playback.issue(tickets.authorizePlayback(id, ticket));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").body(grant);
    }
}
