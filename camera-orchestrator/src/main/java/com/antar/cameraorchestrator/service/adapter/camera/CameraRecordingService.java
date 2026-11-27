package com.antar.cameraorchestrator.service.adapter.camera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.antar.cameraorchestrator.config.CameraConfig;
import com.antar.cameraorchestrator.grpc.NasOrchestratorGrpcClient;
import com.antar.cameraorchestrator.model.dto.RecordingItemDto;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CameraRecordingService {

    private static final Logger log = LoggerFactory.getLogger(CameraRecordingService.class);
    private final CameraConfig cameraConfig;
    private final NasOrchestratorGrpcClient nasGrpcClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ExecutorService recordingExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ha-camera-recorder");
        t.setDaemon(true);
        return t;
    });

    public CameraRecordingService(CameraConfig cameraConfig, NasOrchestratorGrpcClient nasGrpcClient) {
        this.cameraConfig = cameraConfig;
        this.nasGrpcClient = nasGrpcClient;
    }

    /**
     * Resolves the executable path for FFmpeg.
     */
    private String resolveFfmpegPath() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Path winFfmpeg = Paths.get("C:\\ffmpeg\\ffmpeg.exe");
            if (Files.exists(winFfmpeg)) {
                return winFfmpeg.toString();
            }
        } else {
            Path linuxFfmpeg = Paths.get("/usr/bin/ffmpeg");
            if (Files.exists(linuxFfmpeg)) {
                return linuxFfmpeg.toString();
            }
        }
        return "ffmpeg";
    }

    /**
     * Constructs the primary RTSP URL with authentication.
     */
    private String getCameraRtspUrl() {
        String encodedPassword = URLEncoder.encode(cameraConfig.getPassword(), StandardCharsets.UTF_8);
        return String.format("rtsp://%s:%s@%s:%d%s",
                cameraConfig.getUsername(),
                encodedPassword,
                cameraConfig.getDefaultIp(),
                cameraConfig.getRtspPort(),
                cameraConfig.getMainstreamPath());
    }

    /**
     * Captures a live clip from the camera using FFmpeg with -movflags +faststart and AAC audio,
     * then uploads it to video-service for TrueNAS SMB archiving and Kafka HLS encoding.
     */
    public CompletableFuture<RecordingItemDto> recordAndUploadToTrueNas(String entityId, int durationSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            int duration = Math.max(5, Math.min(durationSeconds, 180));
            String cleanEntity = entityId.replace("camera.", "").replace(".", "_");
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String recordingId = "rec_" + cleanEntity + "_" + timestamp;
            String filename = cleanEntity + "_" + timestamp + ".mp4";

            Path tempFile = null;
            try {
                tempFile = Files.createTempFile("antar_rec_", ".mp4");
                String ffmpegPath = resolveFfmpegPath();
                String rtspUrl = getCameraRtspUrl();

                log.info("Starting FFmpeg clip capture [id={}] for {}s from {}", recordingId, duration, cameraConfig.getDefaultIp());

                List<String> cmd = Arrays.asList(
                        ffmpegPath,
                        "-y",
                        "-rtsp_transport", "tcp",
                        "-i", rtspUrl,
                        "-t", String.valueOf(duration),
                        "-c:v", "copy",
                        "-c:a", "aac",
                        "-movflags", "+faststart",
                        tempFile.toAbsolutePath().toString()
                );

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                // Consume process output in background to prevent buffer deadlock
                CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                        return sb.toString();
                    } catch (IOException e) {
                        return "";
                    }
                });

                boolean finished = process.waitFor(duration + 20L, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("FFmpeg capture timed out after " + (duration + 20) + " seconds");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    String output = outputFuture.get(2, TimeUnit.SECONDS);
                    throw new RuntimeException("FFmpeg exited with code " + exitCode + ": " + output);
                }

                long fileSizeBytes = Files.size(tempFile);
                log.info("Captured clip [id={}, size={} bytes]. Uploading to nas-orchestrator via gRPC...", recordingId, fileSizeBytes);

                // Upload directly to nas-orchestrator Shared folder over authenticated gRPC
                nasGrpcClient.uploadFile("shared/" + filename, tempFile.toFile());

                log.info("Successfully recorded and stored clip [id={}] in TrueNAS!", recordingId);
                return new RecordingItemDto(recordingId, filename, fileSizeBytes, System.currentTimeMillis(), "RAW");

            } catch (Exception e) {
                log.error("Failed recording/uploading clip for [id={}]: {}", recordingId, e.getMessage(), e);
                throw new RuntimeException("Recording failed: " + e.getMessage(), e);
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {}
                }
            }
        }, recordingExecutor);
    }

    /**
     * Lists all recorded video sessions stored on TrueNAS SMB under 'raw/'.
     */
    public List<RecordingItemDto> listRecordings() {
        List<RecordingItemDto> list = new ArrayList<>();
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/list?path=raw";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("nas-orchestrator returned HTTP {} for list: {}", response.statusCode(), response.body());
                return list;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode entries = root.path("entry");
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    String rawName = entry.path("name").asText("");
                    if (rawName.endsWith("/")) {
                        String recordingId = rawName.substring(0, rawName.length() - 1);
                        // Only camera recordings created by IoT camera capture start with 'rec_'
                        if (recordingId.startsWith("rec_")) {
                            RecordingItemDto item = inspectRecordingFolder(recordingId);
                            if (item != null) {
                                list.add(item);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list recordings from TrueNAS: {}", e.getMessage());
        }

        // Sort newest first
        list.sort((a, b) -> b.getId().compareTo(a.getId()));
        return list;
    }

    /**
     * Inspects a specific recording folder inside 'raw/{recordingId}'.
     */
    private RecordingItemDto inspectRecordingFolder(String recordingId) {
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/list?path=raw/" + recordingId;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode entries = root.path("entry");
                if (entries.isArray()) {
                    for (JsonNode f : entries) {
                        String fname = f.path("name").asText("");
                        if (fname.toLowerCase().endsWith(".mp4")) {
                            long size = f.path("size").asLong(0);
                            long lastMod = f.path("lastModified").asLong(0);
                            String status = isHlsStreamReady(recordingId) ? "ENCODED" : "READY";
                            return new RecordingItemDto(recordingId, fname, size, lastMod, status);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect folder raw/{}: {}", recordingId, e.getMessage());
        }
        return null;
    }

    /**
     * Checks whether the encoded HLS master playlist exists in nas-orchestrator via gRPC.
     */
    private boolean isHlsStreamReady(String recordingId) {
        try {
            String content = nasGrpcClient.getRewrittenPlaylist("encoded/" + recordingId + "/master.m3u8", "");
            return content != null && !content.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Proxies the raw MP4 file from TrueNAS as an attachment download via gRPC.
     */
    public void downloadRecording(String recordingId, HttpServletResponse response) throws IOException {
        RecordingItemDto item = inspectRecordingFolder(recordingId);
        if (item == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Recording not found");
            return;
        }

        String path = "raw/" + recordingId + "/" + item.getFilename();
        nasGrpcClient.downloadToResponse(path, response, "video/mp4", "attachment; filename=\"" + item.getFilename() + "\"");
    }

    /**
     * Proxies the raw MP4 file inline for direct HTML5 browser video playback via gRPC.
     */
    public void previewRecording(String recordingId, HttpServletResponse response) throws IOException {
        RecordingItemDto item = inspectRecordingFolder(recordingId);
        if (item == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Recording not found");
            return;
        }

        String path = "raw/" + recordingId + "/" + item.getFilename();
        nasGrpcClient.downloadToResponse(path, response, "video/mp4", "inline; filename=\"" + item.getFilename() + "\"");
    }

    /**
     * Deletes a recording and its associated HLS packages from TrueNAS SMB storage.
     */
    public boolean deleteRecording(String recordingId) {
        boolean success = true;
        try {
            String rawUrl = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/delete?path=" + URLEncoder.encode("raw/" + recordingId, StandardCharsets.UTF_8);
            HttpRequest rawReq = HttpRequest.newBuilder().uri(URI.create(rawUrl)).timeout(Duration.ofSeconds(10)).DELETE().build();
            httpClient.send(rawReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Error deleting raw recording {}: {}", recordingId, e.getMessage());
            success = false;
        }

        try {
            String encodedUrl = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/delete?path=" + URLEncoder.encode("encoded/" + recordingId, StandardCharsets.UTF_8);
            HttpRequest encReq = HttpRequest.newBuilder().uri(URI.create(encodedUrl)).timeout(Duration.ofSeconds(10)).DELETE().build();
            httpClient.send(encReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("No encoded directory to delete for {}", recordingId);
        }

        return success;
    }

    /**
     * Fetches and rewrites the master HLS playlist from nas-orchestrator via gRPC.
     */
    public String getHlsMasterPlaylist(String recordingId) throws IOException {
        try {
            String body = nasGrpcClient.getRewrittenPlaylist("encoded/" + recordingId + "/master.m3u8", "");
            if (body != null && !body.isBlank()) {
                body = body.replaceAll("(?m)^.*EXT-X-VERSION:3.*$", "#EXT-X-VERSION:3");
                return body.replace("/api/v1/nas-orchestrator/stream/", "/api/camera/recordings/hls/");
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed fetching master playlist via gRPC for {}: {}", recordingId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches and rewrites sub-playlists (e.g. 1080p, 720p) from nas-orchestrator via gRPC.
     */
    public String getHlsSubPlaylist(String nasPath) throws IOException {
        try {
            String body = nasGrpcClient.getRewrittenPlaylist(nasPath, "");
            if (body == null || body.isBlank()) {
                return null;
            }
            return body.replace("/api/v1/nas-orchestrator/stream/", "/api/camera/recordings/hls/");
        } catch (Exception e) {
            log.warn("Failed fetching sub-playlist via gRPC for {}: {}", nasPath, e.getMessage());
            return null;
        }
    }

    /**
     * Proxies TS video segments from TrueNAS SMB via nas-orchestrator.
     */
    public void streamHlsSegment(String nasPath, HttpServletResponse response) throws IOException {
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/stream/segment?path=" + URLEncoder.encode(nasPath, StandardCharsets.UTF_8);
        forwardStream(url, response, "video/MP2T", null);
    }

    private void forwardStream(String sourceUrl, HttpServletResponse response, String contentType, String contentDisposition) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(sourceUrl).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);

        int code = conn.getResponseCode();
        if (code >= 300) {
            response.sendError(code, "Upstream storage error");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        if (contentDisposition != null) {
            response.setHeader("Content-Disposition", contentDisposition);
        }

        long length = conn.getContentLengthLong();
        if (length > 0) {
            response.setContentLengthLong(length);
        }

        try (InputStream in = conn.getInputStream(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        }
    }
}
