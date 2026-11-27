package com.antar.streamingservice.service;

import com.antar.grpc.streaming.PlaylistRequest;
import com.antar.grpc.streaming.PlaylistResponse;
import com.antar.grpc.streaming.StreamingGrpcServiceGrpc;
import com.antar.streamingservice.config.ServiceTokenRefresher;
import com.antar.streamingservice.grpc.PerCallTokenAttacher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingService {
    private final StreamingGrpcServiceGrpc.StreamingGrpcServiceBlockingStub streamingGrpcStub;
    private final PerCallTokenAttacher tokenAttacher;
    private final ServiceTokenRefresher serviceTokenRefresher;

    /**
     * Proxies the HLS master (or variant) playlist from nas-orchestrator.
     *
     * @param path        NAS-relative path to the playlist file,
     *                    e.g. "encoded/{movieId}/master.m3u8" (resolved from Redis by the controller)
     * @param clientToken Optional client token to embed in rewritten playlist/segment URLs
     * @return raw M3U8 content returned by nas-orchestrator
     */
    public String getPlaylist(String path, String clientToken) {
        log.info("Fetching playlist from nas-orchestrator for path: {}", path);

        // Always authenticate the gRPC call with this service's M2M token (aud: nas-orchestrator).
        String serviceToken = serviceTokenRefresher.get();

        PlaylistResponse response = tokenAttacher.attach(streamingGrpcStub, serviceToken)
            .getRewrittenPlaylist(
                PlaylistRequest.newBuilder()
                    .setPath(path)
                    .setToken(clientToken != null ? clientToken : "")
                    .build()
            );

        return response.getContent();
    }
}
