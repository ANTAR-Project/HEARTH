package com.antar.cameraorchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.antar.cameraorchestrator.config.CameraConfig;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class NasStorageService {

    private static final Logger log = LoggerFactory.getLogger(NasStorageService.class);

    private final CameraConfig cameraConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final RestTemplate restTemplate;

    public NasStorageService(CameraConfig cameraConfig) {
        this.cameraConfig = cameraConfig;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(60_000);
        requestFactory.setReadTimeout(15 * 60_000);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    /**
     * Lists files in the given folder (e.g., 'media' or root) from TrueNAS SMB storage.
     */
    public List<Map<String, Object>> listFiles(String folder) {
        String cleanPath = folder == null ? "" : folder.trim();
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/list?path="
                + URLEncoder.encode(cleanPath, StandardCharsets.UTF_8);

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode entries = root.path("entry");
                if (entries.isArray()) {
                    for (JsonNode entry : entries) {
                        String name = entry.path("name").asText("");
                        if (name.isBlank() || name.equals("/") || name.endsWith("/")) {
                            continue;
                        }
                        long size = entry.path("size").asLong(0);
                        boolean isDir = entry.path("isDirectory").asBoolean(false);
                        long lastModified = entry.path("lastModified").asLong(0);

                        String relativePath = cleanPath.isEmpty() ? name : (cleanPath.endsWith("/") ? cleanPath + name : cleanPath + "/" + name);
                        String ext = getExtension(name);
                        String fileType = resolveFileType(ext);

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", name);
                        item.put("path", relativePath);
                        item.put("sizeBytes", size);
                        item.put("formattedSize", formatFileSize(size));
                        item.put("isDirectory", isDir);
                        item.put("lastModified", lastModified > 0 ? new Date(lastModified).toString() : "—");
                        item.put("extension", ext);
                        item.put("fileType", fileType);
                        item.put("downloadUrl", "/api/nas/download?path=" + URLEncoder.encode(relativePath, StandardCharsets.UTF_8));
                        item.put("previewUrl", "/api/nas/preview?path=" + URLEncoder.encode(relativePath, StandardCharsets.UTF_8));

                        results.add(item);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error listing TrueNAS files for path '{}': {}", cleanPath, e.getMessage());
        }

        if ("media".equalsIgnoreCase(cleanPath)) {
            // Include client media videos from TrueNAS raw/ (excluding camera rec_* clips)
            results.addAll(listMediaVideos());
        }

        // Sort folders first, then files by name
        results.sort((a, b) -> {
            boolean isDirA = (boolean) a.get("isDirectory");
            boolean isDirB = (boolean) b.get("isDirectory");
            if (isDirA != isDirB) return isDirA ? -1 : 1;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        return results;
    }

    private List<Map<String, Object>> listMediaVideos() {
        List<Map<String, Object>> list = new ArrayList<>();
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/list?path=raw";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return list;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode entries = root.path("entry");
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    String rawName = entry.path("name").asText("");
                    if (rawName.endsWith("/")) {
                        String movieId = rawName.substring(0, rawName.length() - 1);
                        // Only non-rec_ folders belong to Media Vault (camera recordings start with rec_)
                        if (!movieId.startsWith("rec_")) {
                            Map<String, Object> videoItem = inspectMediaVideo(movieId);
                            if (videoItem != null) {
                                list.add(videoItem);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list media videos from TrueNAS raw/: {}", e.getMessage());
        }

        return list;
    }

    private Map<String, Object> inspectMediaVideo(String movieId) {
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/list?path=raw/" + movieId;
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
                        String ext = getExtension(fname);
                        if (resolveFileType(ext).equals("video")) {
                            long size = f.path("size").asLong(0);
                            long lastMod = f.path("lastModified").asLong(0);

                            boolean isEncoded = checkEncoded(movieId);

                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("name", fname);
                            item.put("path", "raw/" + movieId + "/" + fname);
                            item.put("movieId", movieId);
                            item.put("sizeBytes", size);
                            item.put("formattedSize", formatFileSize(size));
                            item.put("isDirectory", false);
                            item.put("lastModified", lastMod > 0 ? new Date(lastMod).toString() : "—");
                            item.put("extension", ext);
                            item.put("fileType", "video");
                            item.put("status", isEncoded ? "ENCODED" : "PROCESSING");
                            item.put("downloadUrl", "/api/camera/recordings/" + movieId + "/download");
                            item.put("previewUrl", "/api/camera/recordings/" + movieId + "/preview");
                            item.put("streamUrl", isEncoded ? "/api/camera/recordings/" + movieId + "/stream" : null);

                            return item;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not inspect media video raw/{}: {}", movieId, e.getMessage());
        }
        return null;
    }

    private boolean checkEncoded(String movieId) {
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/list?path=encoded/" + movieId;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("master.m3u8");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Uploads a file to TrueNAS SMB storage. Videos are routed through video-service
     * to trigger TrueNAS Kafka multi-bitrate HLS encoding.
     */
    public Map<String, Object> uploadFile(String folder, MultipartFile file, boolean autoEncodeVideo) throws IOException {
        String targetFolder = (folder == null || folder.isBlank()) ? "media" : folder.trim();
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "uploaded_file_" + System.currentTimeMillis();
        }
        final String finalFilename = originalFilename;
        String ext = getExtension(finalFilename);
        boolean isVideo = resolveFileType(ext).equals("video");

        File tempFile = File.createTempFile("nas_up_", "_" + finalFilename);
        try {
            file.transferTo(tempFile);

            FileSystemResource fileResource = new FileSystemResource(tempFile) {
                @Override
                public String getFilename() {
                    return finalFilename;
                }
            };

            if (isVideo) {
                // Video upload -> Submit to video-service for TrueNAS Kafka multi-bitrate HLS encoding!
                String cleanName = finalFilename.replaceAll("[^a-zA-Z0-9_-]", "_");
                if (cleanName.endsWith(".mp4")) cleanName = cleanName.substring(0, cleanName.length() - 4);
                String movieId = "media_" + cleanName + "_" + System.currentTimeMillis();

                String uploadUrl = cameraConfig.getVideoServiceUrl() + "/api/v1/videos/upload/" + movieId;
                log.info("Submitting video to video-service for TrueNAS Kafka HLS encoding [movieId={}]: {}", movieId, uploadUrl);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);

                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("file", fileResource);

                HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
                restTemplate.postForEntity(uploadUrl, requestEntity, String.class);

                Map<String, Object> res = new HashMap<>();
                res.put("status", "success");
                res.put("message", "Video uploaded to TrueNAS and queued for Kafka multi-bitrate HLS encoding");
                res.put("movieId", movieId);
                res.put("path", "raw/" + movieId + "/" + finalFilename);
                res.put("encoded", true);
                return res;
            } else {
                // Non-video files (images, PDFs, documents) -> Store directly in media/ folder
                String uploadUrl = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/upload/file?path="
                        + URLEncoder.encode(targetFolder, StandardCharsets.UTF_8);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);

                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("file", fileResource);

                HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
                restTemplate.postForEntity(uploadUrl, requestEntity, String.class);

                Map<String, Object> res = new HashMap<>();
                res.put("status", "success");
                res.put("message", "File uploaded successfully to TrueNAS vault in " + targetFolder);
                res.put("path", targetFolder + "/" + finalFilename);
                res.put("encoded", false);
                return res;
            }
        } finally {
            try {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Deletes a file from TrueNAS SMB storage. Handles both media/ files and raw/ video folders.
     */
    public boolean deleteFile(String path) {
        if (path.startsWith("raw/")) {
            String[] parts = path.split("/");
            if (parts.length >= 2) {
                String movieId = parts[1];
                deleteFolderQuietly("raw/" + movieId);
                deleteFolderQuietly("encoded/" + movieId);
                return true;
            }
        }
        return deleteFolderQuietly(path);
    }

    private boolean deleteFolderQuietly(String path) {
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/delete?path="
                + URLEncoder.encode(path, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 || response.statusCode() == 204;
        } catch (Exception e) {
            log.error("Failed to delete TrueNAS path '{}': {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * Proxies inline preview for images, PDFs, text, and videos with proper MIME type.
     */
    public void previewFile(String path, HttpServletResponse response) throws IOException {
        String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String ext = getExtension(filename);
        String contentType = resolveContentType(ext);

        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/download?path="
                + URLEncoder.encode(path, StandardCharsets.UTF_8);

        forwardStream(url, response, contentType, "inline; filename=\"" + filename + "\"");
    }

    /**
     * Proxies attachment download for files.
     */
    public void downloadFile(String path, HttpServletResponse response) throws IOException {
        String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String url = cameraConfig.getNasOrchestratorUrl() + "/api/v1/nas-orchestrator/files/download?path="
                + URLEncoder.encode(path, StandardCharsets.UTF_8);

        forwardStream(url, response, MediaType.APPLICATION_OCTET_STREAM_VALUE, "attachment; filename=\"" + filename + "\"");
    }

    private void forwardStream(String sourceUrl, HttpServletResponse response, String contentType, String contentDisposition) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(sourceUrl).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);

        int code = conn.getResponseCode();
        if (code >= 300) {
            response.sendError(code, "TrueNAS Storage error");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        response.setHeader("Accept-Ranges", "bytes");
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

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot == -1 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private String resolveFileType(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg", "png", "webp", "gif", "svg", "bmp" -> "image";
            case "pdf" -> "pdf";
            case "mp4", "mkv", "avi", "mov", "webm" -> "video";
            case "mp3", "wav", "aac", "flac", "ogg" -> "audio";
            case "txt", "md", "json", "csv", "xml", "log", "yaml", "yml" -> "document";
            default -> "file";
        };
    }

    private String resolveContentType(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mp3" -> "audio/mpeg";
            case "json" -> "application/json";
            case "txt", "log" -> "text/plain";
            case "md" -> "text/markdown";
            case "csv" -> "text/csv";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
