package com.antar.cameraorchestrator.controller.api;

import com.antar.cameraorchestrator.config.CameraConfig;
import com.antar.cameraorchestrator.model.dto.ServiceCallRequest;
import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.model.entity.EntityStateEntity;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import com.antar.cameraorchestrator.repository.EntityStateRepository;
import com.antar.cameraorchestrator.service.adapter.camera.CameraAdapterService;
import com.antar.cameraorchestrator.service.dispatcher.ServiceDispatcher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import com.antar.cameraorchestrator.model.dto.RecordingItemDto;
import com.antar.cameraorchestrator.service.adapter.camera.CameraRecordingService;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/camera")
public class CameraApiController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CameraApiController.class);

    private final CameraAdapterService cameraAdapterService;
    private final CameraRecordingService cameraRecordingService;
    private final EntityStateRepository entityRepository;
    private final DeviceRepository deviceRepository;
    private final CameraConfig cameraConfig;
    private final ServiceDispatcher serviceDispatcher;

    public CameraApiController(CameraAdapterService cameraAdapterService,
                               CameraRecordingService cameraRecordingService,
                               EntityStateRepository entityRepository,
                               DeviceRepository deviceRepository,
                               CameraConfig cameraConfig,
                               ServiceDispatcher serviceDispatcher) {
        this.cameraAdapterService = cameraAdapterService;
        this.cameraRecordingService = cameraRecordingService;
        this.entityRepository = entityRepository;
        this.deviceRepository = deviceRepository;
        this.cameraConfig = cameraConfig;
        this.serviceDispatcher = serviceDispatcher;
    }

    /**
     * Proxies the camera snapshot image.
     */
    @GetMapping(value = "/{entityId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getSnapshot(@PathVariable String entityId) {
        String deviceId = resolveDeviceId(entityId);
        byte[] imageBytes = cameraAdapterService.fetchSnapshot(deviceId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        headers.setContentLength(imageBytes.length);

        return new ResponseEntity<>(imageBytes, headers, HttpStatus.OK);
    }

    /**
     * Production HLS Live Stream Playlist (.m3u8).
     * Proxies go2rtc's master or variant HLS playlists and rewrites segment links to proxy through this controller,
     * ensuring clients on VLAN 40 never connect directly to internal ports.
     */
    @GetMapping(value = {
            "/{entityId}/live/index.m3u8",
            "/{entityId}/live/hls/playlist.m3u8",
            "/{entityId}/live/playlist.m3u8"
    }, produces = "application/x-mpegURL")
    public ResponseEntity<String> getLiveHlsPlaylist(
            @PathVariable String entityId,
            jakarta.servlet.http.HttpServletRequest request) {
        cameraAdapterService.ensureCameraStreamRegistered(entityId);
        String streamName = cameraAdapterService.resolveStreamName(entityId);
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();

        try {
            String rawM3u8;
            if (uri.endsWith("playlist.m3u8")) {
                // Fetch variant playlist from go2rtc: /api/hls/playlist.m3u8?{query}
                String path = "hls/playlist.m3u8" + (queryString != null && !queryString.isBlank() ? "?" + queryString : "");
                try {
                    byte[] bytes = cameraAdapterService.fetchHlsSegment(path);
                    rawM3u8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // Session expired or reset - recover by fetching active master playlist
                    log.debug("Variant playlist expired ({}), regenerating from active stream", queryString);
                    rawM3u8 = cameraAdapterService.fetchHlsPlaylist(streamName, "mp4");
                }
            } else {
                // Fetch master playlist: /api/stream.m3u8?src={streamName}&{query}
                rawM3u8 = cameraAdapterService.fetchHlsPlaylist(streamName, queryString);
            }

            if (rawM3u8 == null || rawM3u8.isBlank() || !rawM3u8.contains("#EXTM3U")) {
                String initializingM3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n";
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType("application/x-mpegURL"));
                headers.setCacheControl("no-cache, no-store, must-revalidate");
                return new ResponseEntity<>(initializingM3u8, headers, HttpStatus.OK);
            }

            String rewrittenM3u8 = rewriteHlsPlaylist(rawM3u8, entityId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-mpegURL"));
            headers.setCacheControl("no-cache, no-store, must-revalidate");
            return new ResponseEntity<>(rewrittenM3u8, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.warn("Error fetching HLS playlist for camera [{}] (uri {}): {}", entityId, uri, e.getMessage());
            String fallbackM3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-mpegURL"));
            headers.setCacheControl("no-cache, no-store, must-revalidate");
            return new ResponseEntity<>(fallbackM3u8, headers, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * HLS Video Segment chunk proxy (by query parameter path).
     */
    @GetMapping(value = "/{entityId}/live/segment")
    public ResponseEntity<byte[]> getLiveHlsSegmentByPath(
            @PathVariable String entityId,
            @RequestParam("path") String path) {
        return serveHlsSegment(entityId, path);
    }

    /**
     * HLS Video Segment chunk proxy (by path variable, matching direct or hls/ subpaths).
     */
    @GetMapping(value = {
            "/{entityId}/live/{segmentName:.*\\.(?:ts|mp4|m4s)}",
            "/{entityId}/live/hls/{segmentName:.*\\.(?:ts|mp4|m4s)}"
    })
    public ResponseEntity<byte[]> getLiveHlsSegmentByName(
            @PathVariable String entityId,
            @PathVariable String segmentName,
            jakarta.servlet.http.HttpServletRequest request) {
        String queryString = request.getQueryString();
        String path = segmentName + (queryString != null && !queryString.isBlank() ? "?" + queryString : "");
        return serveHlsSegment(entityId, path);
    }

    private ResponseEntity<byte[]> serveHlsSegment(String entityId, String path) {
        try {
            byte[] segmentBytes = cameraAdapterService.fetchHlsSegment(path);
            HttpHeaders headers = new HttpHeaders();
            if (path.contains(".mp4") || path.contains(".m4s")) {
                headers.setContentType(MediaType.parseMediaType("video/mp4"));
            } else {
                headers.setContentType(MediaType.parseMediaType("video/MP2T"));
            }
            headers.setCacheControl("public, max-age=60");
            headers.setContentLength(segmentBytes.length);
            return new ResponseEntity<>(segmentBytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.warn("Error fetching HLS segment [{}] for camera [{}]: {}", path, entityId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Rewrites raw HLS playlist content so that all variant playlists and segment links
     * route through /api/camera/{entityId}/live/... without leaking internal ports.
     */
    private String rewriteHlsPlaylist(String m3u8, String entityId) {
        if (m3u8 == null || m3u8.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = m3u8.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                sb.append("\n");
                continue;
            }
            if (trimmed.startsWith("#EXT-X-MAP:")) {
                // Rewrite URI="init.mp4?..." into /api/camera/{entityId}/live/init.mp4?...
                String rewritten = trimmed.replaceAll("URI=\"([^\"]+)\"", "URI=\"/api/camera/" + entityId + "/live/$1\"");
                sb.append(rewritten).append("\n");
            } else if (trimmed.startsWith("#")) {
                sb.append(trimmed).append("\n");
            } else {
                String cleanPath = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
                if (cleanPath.startsWith("api/")) {
                    cleanPath = cleanPath.substring(4);
                }
                sb.append("/api/camera/").append(entityId).append("/live/").append(cleanPath).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Continuous live stream endpoint (MJPEG fallback).
     */
    @GetMapping(value = "/{entityId}/stream")
    public void streamLiveVideo(@PathVariable String entityId,
                                jakarta.servlet.http.HttpServletRequest request,
                                jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        String deviceId = resolveDeviceId(entityId);
        String streamName = cameraAdapterService.resolveStreamName(entityId);
        cameraAdapterService.ensureCameraStreamRegistered(entityId);

        response.setContentType("multipart/x-mixed-replace; boundary=--frame");
        java.io.OutputStream out = response.getOutputStream();

        // 1. Try proxying high-fps stream from local go2rtc instance
        try {
            String go2rtcBase = cameraConfig.getGo2rtcUrl() != null ? cameraConfig.getGo2rtcUrl() : "http://localhost:1984";
            java.net.URI uri = java.net.URI.create(go2rtcBase + "/api/stream.mjpeg?src=" + java.net.URLEncoder.encode(streamName, java.nio.charset.StandardCharsets.UTF_8));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == 200) {
                try (java.io.InputStream in = conn.getInputStream()) {
                    byte[] buffer = new byte[16384];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        out.flush();
                    }
                    return;
                }
            }
        } catch (Exception ignored) {
            // go2rtc stream not active or interrupted, fall back to snapshot loop
        }

        // 2. Fallback: snapshot frame push loop
        try {
            for (int i = 0; i < 300; i++) {
                byte[] frame = cameraAdapterService.fetchSnapshot(deviceId);
                if (frame != null && frame.length > 0) {
                    out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n").getBytes());
                    out.write(frame);
                    out.write("\r\n".getBytes());
                    out.flush();
                }
                Thread.sleep(150); // ~7 fps fallback
            }
        } catch (Exception ignored) {
            // Client closed connection / stopped streaming
        }
    }

    /**
     * Continuous fragmented MP4 (fMP4) stream endpoint.
     * Streams continuous video with AAC audio directly over HTTP port 8080.
     */
    @GetMapping(value = {
            "/{entityId}/live/stream.mp4",
            "/{entityId}/live/mp4"
    })
    public void streamMp4Video(@PathVariable String entityId,
                               jakarta.servlet.http.HttpServletRequest request,
                               jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        cameraAdapterService.ensureCameraStreamRegistered(entityId);
        String streamName = cameraAdapterService.resolveStreamName(entityId);
        String go2rtcBase = cameraConfig.getGo2rtcUrl() != null ? cameraConfig.getGo2rtcUrl() : "http://localhost:1984";
        URI uri = URI.create(go2rtcBase + "/api/stream.mp4?src=" + java.net.URLEncoder.encode(streamName, java.nio.charset.StandardCharsets.UTF_8));

        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(15000);
        int code = conn.getResponseCode();
        if (code == 200) {
            response.setContentType("video/mp4");
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            try (InputStream in = conn.getInputStream();
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[32768];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();
                }
            } catch (Exception ignored) {
                // Client stopped streaming
            }
        } else {
            response.setStatus(code);
        }
    }

    /**
     * WebRTC WHEP (WebRTC HTTP Egress Protocol) Signaling Proxy.
     * Proxies standard browser SDP offer to go2rtc and returns SDP answer with real-time audio and video.
     */
    @PostMapping(value = "/{entityId}/webrtc")
    public ResponseEntity<String> proxyWebRtcWhep(
            @PathVariable String entityId,
            jakarta.servlet.http.HttpServletRequest request) {
        cameraAdapterService.ensureCameraStreamRegistered(entityId);
        String streamName = cameraAdapterService.resolveStreamName(entityId);

        byte[] offerBytes;
        try {
            offerBytes = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("Failed to read SDP offer body: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Failed to read SDP offer");
        }

        if (offerBytes == null || offerBytes.length == 0) {
            return ResponseEntity.badRequest().body("Empty SDP offer");
        }

        try {
            String go2rtcBase = cameraConfig.getGo2rtcUrl() != null ? cameraConfig.getGo2rtcUrl() : "http://localhost:1984";
            URI uri = URI.create(go2rtcBase + "/api/webrtc?src=" + java.net.URLEncoder.encode(streamName, java.nio.charset.StandardCharsets.UTF_8));
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/sdp");
            conn.setRequestProperty("Accept", "application/sdp");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(offerBytes);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream in = conn.getInputStream()) {
                    String answerSdp = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/sdp"));
                    return new ResponseEntity<>(answerSdp, headers, HttpStatus.valueOf(code));
                }
            } else {
                String errBody = "";
                try (InputStream err = conn.getErrorStream()) {
                    if (err != null) {
                        errBody = new String(err.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                log.warn("go2rtc WHEP returned HTTP {}: {}", code, errBody);
                return ResponseEntity.status(code).body(errBody);
            }
        } catch (Exception e) {
            log.error("Error proxying WebRTC WHEP for camera [{}]: {}", entityId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("WebRTC Signaling Exception: " + e.getMessage());
        }
    }

    /**
     * Returns stream URLs for client players (HLS, MJPEG, Snapshots) strictly through VLAN 10 API.
     */
    @GetMapping("/{entityId}/stream-url")
    public ResponseEntity<Map<String, Object>> getStreamInfo(
            @PathVariable String entityId,
            jakarta.servlet.http.HttpServletRequest request) {
        String deviceId = resolveDeviceId(entityId);
        Optional<DeviceEntity> devOpt = deviceRepository.findById(deviceId);
        String ip = devOpt.map(DeviceEntity::getIpAddress).orElse(cameraConfig.getDefaultIp());

        cameraAdapterService.ensureCameraStreamRegistered(entityId);

        return ResponseEntity.ok(Map.of(
                "entity_id", entityId,
                "device_id", deviceId,
                "hls_stream_url", "/api/camera/" + entityId + "/live/index.m3u8",
                "snapshot_url", "/api/camera/" + entityId + "/snapshot",
                "live_stream_url", "/api/camera/" + entityId + "/stream",
                "substream_rtsp", cameraAdapterService.getRtspSubstreamUrl(ip),
                "mainstream_rtsp", cameraAdapterService.getRtspMainstreamUrl(ip),
                "protocol", "HLS"
        ));
    }

    /**
     * Webhook endpoint to ingest camera motion alerts (e.g. from camera HTTP CGI or external listener).
     */
    @PostMapping("/{entityId}/motion")
    public ResponseEntity<Map<String, Object>> triggerMotion(
            @PathVariable String entityId,
            @RequestBody(required = false) Map<String, Object> body) {

        String deviceId = resolveDeviceId(entityId);
        boolean detected = body == null || !body.containsKey("detected") || Boolean.parseBoolean(String.valueOf(body.get("detected")));

        cameraAdapterService.handleMotionDetected(deviceId, detected);

        return ResponseEntity.ok(Map.of(
                "entity_id", entityId,
                "device_id", deviceId,
                "motion_detected", detected,
                "status", "acknowledged"
        ));
    }

    /**
     * Triggers PTZ movement commands.
     */
    @PostMapping("/{entityId}/ptz")
    public ResponseEntity<Map<String, Object>> executePtz(
            @PathVariable String entityId,
            @RequestBody Map<String, Object> body) {

        String command = String.valueOf(body.getOrDefault("command", "PTZ_STOP"));
        ServiceCallRequest request = new ServiceCallRequest("camera", command.toLowerCase(), entityId, body);
        Map<String, Object> result = serviceDispatcher.dispatch(request);

        return ResponseEntity.ok(result);
    }

    /**
     * Subnet discovery trigger across IoT VLAN.
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scanSubnet(@RequestBody(required = false) Map<String, String> body) {
        String ipOrSubnet = body != null ? body.get("target") : null;
        var discovered = cameraAdapterService.scanForCameras(ipOrSubnet);

        return ResponseEntity.ok(Map.of(
                "count", discovered.size(),
                "cameras", discovered
        ));
    }

    /**
     * Camera adapter status summary.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(cameraAdapterService.getAdapterStatus());
    }

    /**
     * Trigger manual or event-based clip recording and upload to TrueNAS.
     */
    @PostMapping("/{entityId}/record")
    public ResponseEntity<Map<String, Object>> recordClip(
            @PathVariable String entityId,
            @RequestParam(defaultValue = "15") int duration) {
        try {
            RecordingItemDto item = cameraRecordingService.recordAndUploadToTrueNas(entityId, duration).get(duration + 25L, TimeUnit.SECONDS);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Recording stored in TrueNAS and submitted to Kafka encoding pipeline",
                    "recording", item
            ));
        } catch (Exception e) {
            log.error("Recording error for {}: {}", entityId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * List all recorded sessions stored in TrueNAS SMB.
     */
    @GetMapping("/recordings")
    public ResponseEntity<List<RecordingItemDto>> listRecordings() {
        return ResponseEntity.ok(cameraRecordingService.listRecordings());
    }

    /**
     * Download recording from TrueNAS SMB as attachment.
     */
    @GetMapping("/recordings/{recordingId}/download")
    public void downloadRecording(
            @PathVariable String recordingId,
            jakarta.servlet.http.HttpServletResponse response) throws IOException {
        cameraRecordingService.downloadRecording(recordingId, response);
    }

    /**
     * Direct HTML5 video inline preview of TrueNAS recording.
     */
    @GetMapping("/recordings/{recordingId}/preview")
    public void previewRecording(
            @PathVariable String recordingId,
            jakarta.servlet.http.HttpServletResponse response) throws IOException {
        cameraRecordingService.previewRecording(recordingId, response);
    }

    /**
     * HLS Master Playlist proxy for TrueNAS streaming-service.
     */
    @GetMapping(value = "/recordings/{recordingId}/stream", produces = "application/x-mpegURL")
    public ResponseEntity<String> streamRecording(@PathVariable String recordingId) throws IOException {
        String playlist = cameraRecordingService.getHlsMasterPlaylist(recordingId);
        if (playlist == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(playlist);
    }

    /**
     * HLS sub-playlist proxy for TrueNAS streaming.
     */
    @GetMapping(value = "/recordings/hls/playlist", produces = "application/x-mpegURL")
    public ResponseEntity<String> getHlsSubPlaylist(@RequestParam("path") String path) throws IOException {
        String sub = cameraRecordingService.getHlsSubPlaylist(path);
        if (sub == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(sub);
    }

    /**
     * HLS video segment proxy from TrueNAS storage.
     */
    @GetMapping("/recordings/hls/segment")
    public void getHlsSegment(
            @RequestParam("path") String path,
            jakarta.servlet.http.HttpServletResponse response) throws IOException {
        cameraRecordingService.streamHlsSegment(path, response);
    }

    /**
     * Deletes a recording and its transcoded HLS stream from TrueNAS storage.
     */
    @DeleteMapping("/recordings/{recordingId}")
    public ResponseEntity<Map<String, Object>> deleteRecording(@PathVariable String recordingId) {
        boolean deleted = cameraRecordingService.deleteRecording(recordingId);
        if (deleted) {
            return ResponseEntity.ok(Map.of("status", "success", "message", "Recording deleted from TrueNAS"));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Failed to delete recording from TrueNAS"));
        }
    }

    private String resolveDeviceId(String entityId) {
        Optional<EntityStateEntity> entityOpt = entityRepository.findById(entityId);
        if (entityOpt.isPresent() && entityOpt.get().getDeviceId() != null) {
            return entityOpt.get().getDeviceId();
        }
        if (entityId.contains(".")) {
            return entityId.split("\\.")[1];
        }
        return entityId;
    }
}
