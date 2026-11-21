package com.stonewu.fusion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.config.CorsProperties;
import com.stonewu.fusion.controller.media.MediaCopyLinkController;
import com.stonewu.fusion.controller.media.MediaContentController;
import com.stonewu.fusion.controller.media.MediaExceptionHandler;
import com.stonewu.fusion.controller.media.MediaPlaybackController;
import com.stonewu.fusion.entity.media.MediaAccessTicketEntity;
import com.stonewu.fusion.mapper.media.MediaAccessTicketMapper;
import com.stonewu.fusion.service.canvas.CanvasActorResolver;
import com.stonewu.fusion.service.media.MediaAccessTickets;
import com.stonewu.fusion.service.media.MediaContentWriter;
import com.stonewu.fusion.service.media.MediaCopyLinks;
import com.stonewu.fusion.service.media.ObjectStorageMediaPlayback;
import com.stonewu.fusion.service.media.model.MediaException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MediaSecurityBoundaryTests {
    @Configuration
    @EnableWebMvc
    static class Web {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean CorsProperties cors() { return new CorsProperties(); }
        @Bean TokenAuthenticationFilter tokens() {
            return new TokenAuthenticationFilter(mock(TokenService.class), mock(SecurityUserDetailsService.class));
        }
        @Bean MediaAccessTickets tickets() {
            var tickets = mock(MediaAccessTickets.class);
            when(tickets.authorize(anyString(), anyString())).thenThrow(new MediaException(403, "MEDIA_TICKET_INVALID", "无效凭据"));
            when(tickets.authorizePlayback(anyString(), anyString()))
                    .thenThrow(new MediaException(403, "MEDIA_TICKET_INVALID", "无效凭据"));
            return tickets;
        }
        @Bean
        ObjectStorageMediaPlayback playback() {
            return mock(ObjectStorageMediaPlayback.class);
        }

        @Bean
        MediaPlaybackController playbackController(MediaAccessTickets tickets, ObjectStorageMediaPlayback playback) {
            return new MediaPlaybackController(tickets, playback);
        }

        @Bean
        MediaCopyLinks copyLinks() {
            return mock(MediaCopyLinks.class);
        }

        @Bean
        MediaCopyLinkController copyLinkController(MediaCopyLinks copyLinks) {
            return new MediaCopyLinkController(mock(CanvasActorResolver.class), copyLinks);
        }

        @Bean MediaContentController content(MediaAccessTickets tickets) {
            return new MediaContentController(tickets, mock(MediaContentWriter.class));
        }
        @Bean MediaExceptionHandler advice() { return new MediaExceptionHandler(); }
    }

    @Test
    void onlyGetAndHeadReachTheTicketGateWhileCreationAndAccessIssuanceRequireLogin() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Web.class, SecurityConfig.class);
            context.refresh();
            var http = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            http.perform(get("/api/media-content/test-id").param("ticket", "bad")).andExpect(status().isForbidden());
            verify(context.getBean(MediaAccessTickets.class)).authorize("test-id", "bad");
            http.perform(head("/api/media-content/test-id").param("ticket", "bad")).andExpect(status().isForbidden());
            http.perform(post("/api/media-content/test-id").param("ticket", "bad")).andExpect(status().isUnauthorized());
            http.perform(get("/api/media-content/test-id/playback").param("ticket", "bad"))
                    .andExpect(status().isForbidden());
            verify(context.getBean(MediaAccessTickets.class)).authorizePlayback("test-id", "bad");
            verifyNoInteractions(context.getBean(ObjectStorageMediaPlayback.class));
            http.perform(post("/api/media-content/test-id/playback").param("ticket", "bad"))
                    .andExpect(status().isUnauthorized());
            http.perform(post("/api/media-resources/test-id/access")).andExpect(status().isUnauthorized());
            http.perform(post("/api/media-resources/20000000-0000-0000-0000-000000000001/copy-link"))
                    .andExpect(status().isUnauthorized());
            http.perform(post("/api/media-resources/20000000-0000-0000-0000-000000000001/download-link"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(context.getBean(MediaCopyLinks.class));
            http.perform(post("/api/media-resources/upload")).andExpect(status().isUnauthorized());
            http.perform(get("/api/media-resources/test-id")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void generationAndDownloadTicketsCannotAuthorizeBrowserDecryption() {
        var mapper = mock(MediaAccessTicketMapper.class);
        var playback = mock(ObjectStorageMediaPlayback.class);
        var tickets = new MediaAccessTickets(mapper, null, null, null, null, playback);
        String token = "a".repeat(43);
        when(mapper.authorize(anyString(), anyString())).thenReturn(
                MediaAccessTicketEntity.builder().purpose("GENERATION").download(false).build(),
                MediaAccessTicketEntity.builder().purpose("BROWSER").download(true).build());
        assertThatThrownBy(() -> tickets.authorizePlayback("id", token)).isInstanceOf(MediaException.class);
        assertThatThrownBy(() -> tickets.authorizePlayback("id", token)).isInstanceOf(MediaException.class);
        verifyNoInteractions(playback);
    }
}
