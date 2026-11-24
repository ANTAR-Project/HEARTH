package com.homeapp.controller.api;

import com.homeapp.service.NasStorageService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/nas")
public class NasApiController {

    private final NasStorageService nasStorageService;

    public NasApiController(NasStorageService nasStorageService) {
        this.nasStorageService = nasStorageService;
    }

    /**
     * Lists files and folders inside TrueNAS SMB storage.
     * folder can be 'media' or empty string for root.
     */
    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> listFiles(@RequestParam(value = "folder", defaultValue = "media") String folder) {
        return ResponseEntity.ok(nasStorageService.listFiles(folder));
    }

    /**
     * Uploads a file to TrueNAS SMB storage (defaults to 'media' folder).
     * Optional boolean 'autoEncode' triggers Kafka HLS encoding if the file is a video.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam(value = "folder", defaultValue = "media") String folder,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "autoEncode", defaultValue = "false") boolean autoEncode) {
        try {
            Map<String, Object> result = nasStorageService.uploadFile(folder, file, autoEncode);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Failed to upload file to TrueNAS: " + e.getMessage()));
        }
    }

    /**
     * Deletes a file from TrueNAS SMB storage.
     */
    @DeleteMapping("/files")
    public ResponseEntity<Map<String, Object>> deleteFile(@RequestParam("path") String path) {
        boolean deleted = nasStorageService.deleteFile(path);
        if (deleted) {
            return ResponseEntity.ok(Map.of("status", "success", "message", "File deleted from TrueNAS"));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Failed to delete file from TrueNAS"));
        }
    }

    /**
     * Inline preview for images, PDFs, text, and videos with proper Content-Type.
     */
    @GetMapping("/preview")
    public void previewFile(
            @RequestParam("path") String path,
            HttpServletResponse response) throws IOException {
        nasStorageService.previewFile(path, response);
    }

    /**
     * Downloads file as attachment.
     */
    @GetMapping("/download")
    public void downloadFile(
            @RequestParam("path") String path,
            HttpServletResponse response) throws IOException {
        nasStorageService.downloadFile(path, response);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSizeException(org.springframework.web.multipart.MaxUploadSizeExceededException exc) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("status", "error", "message", "File is too large! Maximum allowed upload size is 2GB."));
    }
}
