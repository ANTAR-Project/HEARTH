package com.homeapp.model.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class RecordingItemDto {
    private String id;
    private String filename;
    private long sizeBytes;
    private String formattedSize;
    private String timestamp;
    private String status; // "READY", "ENCODED", "RECORDING"
    private String downloadUrl;
    private String previewUrl;
    private String streamUrl;

    public RecordingItemDto() {}

    public RecordingItemDto(String id, String filename, long sizeBytes, long lastModifiedMillis, String status) {
        this.id = id;
        this.filename = filename;
        this.sizeBytes = sizeBytes;
        this.status = status;
        this.formattedSize = formatBytes(sizeBytes);
        if (lastModifiedMillis > 0) {
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastModifiedMillis), ZoneId.systemDefault());
            this.timestamp = dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } else {
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        this.downloadUrl = "/api/camera/recordings/" + id + "/download";
        this.previewUrl = "/api/camera/recordings/" + id + "/preview";
        this.streamUrl = "/api/camera/recordings/" + id + "/stream";
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getFormattedSize() { return formattedSize; }
    public void setFormattedSize(String formattedSize) { this.formattedSize = formattedSize; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

    public String getPreviewUrl() { return previewUrl; }
    public void setPreviewUrl(String previewUrl) { this.previewUrl = previewUrl; }

    public String getStreamUrl() { return streamUrl; }
    public void setStreamUrl(String streamUrl) { this.streamUrl = streamUrl; }
}
