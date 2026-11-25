package com.antar.cameraorchestrator.service.adapter.camera;

import com.antar.cameraorchestrator.config.CameraConfig;
import com.antar.cameraorchestrator.model.dto.DeviceCommand;
import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.model.entity.EntityStateEntity;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import com.antar.cameraorchestrator.repository.EntityStateRepository;
import com.antar.cameraorchestrator.service.StateEngineService;
import com.antar.cameraorchestrator.service.dispatcher.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

@Service
public class CameraAdapterService implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(CameraAdapterService.class);

    private final CameraConfig cameraConfig;
    private final DeviceRepository deviceRepository;
    private final EntityStateRepository entityRepository;
    private final StateEngineService stateEngineService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ha-camera-scheduler");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    // Cache of last generated/retrieved snapshot per deviceId
    private final Map<String, byte[]> snapshotCache = new ConcurrentHashMap<>();
    private volatile byte[] latestCameraFrame = null;
    private final java.util.concurrent.atomic.AtomicBoolean isCapturing = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String activeProfileToken = null;

    private final CameraRecordingService cameraRecordingService;

    public CameraAdapterService(CameraConfig cameraConfig,
                                DeviceRepository deviceRepository,
                                EntityStateRepository entityRepository,
                                StateEngineService stateEngineService,
                                @org.springframework.context.annotation.Lazy CameraRecordingService cameraRecordingService) {
        this.cameraConfig = cameraConfig;
        this.deviceRepository = deviceRepository;
        this.entityRepository = entityRepository;
        this.stateEngineService = stateEngineService;
        this.cameraRecordingService = cameraRecordingService;
    }

    @jakarta.annotation.PostConstruct
    public void startBackgroundCapture() {
        // Auto-discover ONVIF ProfileToken dynamically on startup
        scheduler.schedule(() -> {
            try {
                String ip = cameraConfig.getDefaultIp();
                int port = cameraConfig.getHttpPort();
                resolveOnvifProfileToken(ip, port);
            } catch (Exception e) {
                log.warn("Initial ONVIF probe deferred: {}", e.getMessage());
            }
        }, 1500, TimeUnit.MILLISECONDS);

        // Auto-register configured camera streams with go2rtc
        scheduler.schedule(() -> {
            try {
                registerAllConfiguredCamerasWithGo2rtc();
            } catch (Exception e) {
                log.warn("Initial go2rtc camera stream registration deferred: {}", e.getMessage());
            }
        }, 2000, TimeUnit.MILLISECONDS);

        // Schedule background snapshot refresh from go2rtc (< 20ms)
        scheduler.scheduleWithFixedDelay(this::captureLatestFrame, 3000, 3000, TimeUnit.MILLISECONDS);
    }

    private void captureLatestFrame() {
        if (!isCapturing.compareAndSet(false, true)) {
            return;
        }
        try {
            // Instant go2rtc frame grab (< 30ms, zero CPU overhead)
            byte[] go2rtcFrame = fetchGo2rtcSnapshot("front_porch");
            if (go2rtcFrame != null && go2rtcFrame.length > 1000) {
                latestCameraFrame = go2rtcFrame;
                snapshotCache.put("cpplus_front_cam", go2rtcFrame);
                snapshotCache.put("camera.front_porch_cam", go2rtcFrame);
            }
        } catch (Exception e) {
            log.debug("Background go2rtc frame grab error: {}", e.getMessage());
        } finally {
            isCapturing.set(false);
        }
    }

    @Override
    public String getProtocol() {
        return "CAMERA";
    }

    @Override
    public boolean sendCommand(String deviceId, DeviceCommand command) {
        log.info("Dispatching camera command to device [{}]: state={}, attributes={}",
                deviceId, command.getState(), command.getAttributes());

        String target = command.getState().toUpperCase();
        switch (target) {
            case "SNAPSHOT":
                fetchSnapshot(deviceId);
                return true;
            case "RECORD":
                int duration = 15;
                if (command.getAttributes() != null && command.getAttributes().containsKey("duration")) {
                    try {
                        duration = Integer.parseInt(String.valueOf(command.getAttributes().get("duration")));
                    } catch (Exception ignored) {}
                }
                cameraRecordingService.recordAndUploadToTrueNas(deviceId, duration);
                return true;
            case "PTZ_UP":
            case "PTZ_DOWN":
            case "PTZ_LEFT":
            case "PTZ_RIGHT":
            case "PTZ_STOP":
                int speed = 4;
                if (command.getAttributes() != null && command.getAttributes().containsKey("speed")) {
                    try {
                        speed = Integer.parseInt(String.valueOf(command.getAttributes().get("speed")));
                    } catch (Exception ignored) {}
                }
                return sendPtzCommand(deviceId, target, speed);
            default:
                log.info("Generic camera command [{}] acknowledged for [{}]", target, deviceId);
                return true;
        }
    }

    public CompletableFuture<com.antar.cameraorchestrator.model.dto.RecordingItemDto> recordClip(String deviceId, int durationSeconds) {
        return cameraRecordingService.recordAndUploadToTrueNas(deviceId, durationSeconds);
    }

    /**
     * Dispatches Pan/Tilt movement commands to the CP Plus camera via ONVIF PTZ service.
     */
    public boolean sendPtzCommand(String deviceId, String ptzAction, int speed) {
        Optional<DeviceEntity> devOpt = deviceRepository.findById(deviceId);
        String ip = devOpt.map(DeviceEntity::getIpAddress).orElse(cameraConfig.getDefaultIp());
        int port = cameraConfig.getHttpPort();

        String profileToken = resolveOnvifProfileToken(ip, port);

        double x = 0.0;
        double y = 0.0;
        double step = 0.25;

        switch (ptzAction.toUpperCase()) {
            case "PTZ_UP": y = step; break;
            case "PTZ_DOWN": y = -step; break;
            case "PTZ_LEFT": x = -step; break;
            case "PTZ_RIGHT": x = step; break;
            case "PTZ_STOP":
                sendOnvifPtzStop(ip, port, profileToken);
                return true;
            default: return false;
        }

        log.info("Sending ONVIF RelativeMove (token={}, x={}, y={}) to camera [{}] at {}:{}",
                profileToken, x, y, deviceId, ip, port);
        sendOnvifPtzRelativeMove(ip, port, profileToken, x, y);

        // Invalidate current frame and capture the fresh view after the physical rotation completes
        scheduler.schedule(() -> {
            latestCameraFrame = null;
            captureLatestFrame();
        }, 800, TimeUnit.MILLISECONDS);

        return true;
    }

    public String resolveOnvifProfileToken(String ip, int port) {
        if (activeProfileToken != null && !activeProfileToken.isBlank()) {
            return activeProfileToken;
        }
        try {
            String soap = buildOnvifGetProfilesEnvelope();
            String response = sendSoapRequestWithResponse(ip, port, "/onvif/media_service", soap);
            if (response != null && !response.isBlank()) {
                // Check for standard profile token pattern: token="PROFILE_..."
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("token=\"(PROFILE_[^\"]+)\"").matcher(response);
                if (matcher.find()) {
                    activeProfileToken = matcher.group(1);
                    log.info("Auto-discovered active ONVIF ProfileToken: [{}] for camera at {}:{}", activeProfileToken, ip, port);
                    return activeProfileToken;
                }
                // Fallback to any token
                matcher = java.util.regex.Pattern.compile("token=\"([^\"]+)\"").matcher(response);
                if (matcher.find()) {
                    activeProfileToken = matcher.group(1);
                    log.info("Auto-discovered ONVIF token: [{}] for camera at {}:{}", activeProfileToken, ip, port);
                    return activeProfileToken;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover ONVIF ProfileToken: {}", e.getMessage());
        }

        activeProfileToken = cameraConfig.getOnvifProfileToken();
        if (activeProfileToken == null || activeProfileToken.isBlank()) {
            activeProfileToken = "PROFILE_1676656330";
        }
        log.info("Using configured ONVIF ProfileToken: [{}] for camera at {}:{}", activeProfileToken, ip, port);
        return activeProfileToken;
    }

    private void sendOnvifPtzRelativeMove(String ip, int port, String profileToken, double x, double y) {
        String soap = buildOnvifPtzEnvelope(
                "<tptz:RelativeMove xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
                "<tptz:ProfileToken>" + profileToken + "</tptz:ProfileToken>" +
                "<tptz:Translation>" +
                "<tt:PanTilt x=\"" + x + "\" y=\"" + y + "\" space=\"http://www.onvif.org/ver10/tptz/PanTiltSpaces/TranslationGenericSpace\"/>" +
                "</tptz:Translation>" +
                "</tptz:RelativeMove>"
        );
        sendSoapRequest(ip, port, "/onvif/ptz_service", soap);
    }

    private void sendOnvifPtzStop(String ip, int port, String profileToken) {
        String soap = buildOnvifPtzEnvelope(
                "<tptz:Stop xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\">" +
                "<tptz:ProfileToken>" + profileToken + "</tptz:ProfileToken>" +
                "<tptz:PanTilt>true</tptz:PanTilt>" +
                "</tptz:Stop>"
        );
        sendSoapRequest(ip, port, "/onvif/ptz_service", soap);
    }

    private String buildOnvifGetProfilesEnvelope() {
        return buildOnvifPtzEnvelope("<trt:GetProfiles xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>");
    }

    private String buildOnvifPtzEnvelope(String bodyContent) {
        String username = cameraConfig.getUsername() != null && !cameraConfig.getUsername().isBlank() ? cameraConfig.getUsername() : "admin";
        String password = cameraConfig.getPassword() != null ? cameraConfig.getPassword() : "";

        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        String nonceB64 = Base64.getEncoder().encodeToString(nonce);
        String created = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();

        String digestB64 = "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(nonce);
            md.update(created.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digestB64 = Base64.getEncoder().encodeToString(md.digest());
        } catch (Exception ignored) {}

        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
                "<s:Header>" +
                "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
                "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
                "<wsse:UsernameToken>" +
                "<wsse:Username>" + username + "</wsse:Username>" +
                "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + digestB64 + "</wsse:Password>" +
                "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + nonceB64 + "</wsse:Nonce>" +
                "<wsu:Created>" + created + "</wsu:Created>" +
                "</wsse:UsernameToken>" +
                "</wsse:Security>" +
                "</s:Header>" +
                "<s:Body>" +
                bodyContent +
                "</s:Body>" +
                "</s:Envelope>";
    }

    private void sendSoapRequest(String ip, int port, String path, String soapXml) {
        try {
            URI uri = URI.create("http://" + ip + ":" + port + path);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conn.setRequestProperty("Connection", "close");
            conn.setDoOutput(true);

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(soapXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            // Read response body to check for faults
            try (InputStream respIn = (code >= 400) ? conn.getErrorStream() : conn.getInputStream()) {
                if (respIn != null) {
                    String respBody = new String(respIn.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (respBody.contains("Fault") || respBody.contains("NotAuthorized")) {
                        log.warn("ONVIF SOAP FAULT from {}{}: HTTP {} - {}", ip, path, code,
                                respBody.length() > 300 ? respBody.substring(0, 300) : respBody);
                    } else {
                        log.info("ONVIF SOAP OK from {}{}: HTTP {}", ip, path, code);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ONVIF SOAP request to {}{} failed: {}", ip, path, e.getMessage());
        }
    }

    private String sendSoapRequestWithResponse(String ip, int port, String path, String soapXml) {
        try {
            URI uri = URI.create("http://" + ip + ":" + port + path);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conn.setRequestProperty("Connection", "close");
            conn.setDoOutput(true);

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(soapXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            try (InputStream respIn = (code >= 400) ? conn.getErrorStream() : conn.getInputStream()) {
                if (respIn != null) {
                    return new String(respIn.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            log.warn("ONVIF SOAP query to {}{} failed: {}", ip, path, e.getMessage());
        }
        return null;
    }

    private final Map<String, Long> lastCaptureTimestamp = new ConcurrentHashMap<>();

    /**
     * Resolves the stream identifier used by go2rtc from an entityId or deviceId.
     */
    public String resolveStreamName(String entityOrDeviceId) {
        if (entityOrDeviceId == null || entityOrDeviceId.isBlank()) {
            return "camera.front_porch_cam";
        }
        if (entityOrDeviceId.startsWith("camera.")) {
            return entityOrDeviceId;
        }
        Optional<EntityStateEntity> entityOpt = entityRepository.findByDeviceId(entityOrDeviceId).stream()
                .filter(e -> "camera".equalsIgnoreCase(e.getDomain()))
                .findFirst();
        return entityOpt.map(EntityStateEntity::getId).orElse("camera." + entityOrDeviceId);
    }

    /**
     * Registers all database/configured camera RTSP endpoints dynamically with go2rtc REST API.
     */
    public void registerAllConfiguredCamerasWithGo2rtc() {
        var cameraDevices = deviceRepository.findAll().stream()
                .filter(d -> "CAMERA".equalsIgnoreCase(d.getProtocol()))
                .toList();

        if (cameraDevices.isEmpty()) {
            // Register default camera from config with TCP interleaved transport & audio preserved
            String streamName = "camera.front_porch_cam";
            String ip = cameraConfig.getDefaultIp();
            String rtspUrl = buildRealRtspUrl(ip, cameraConfig.getMainstreamPath()) + "#backchannel=0#transport=tcp#timeout=30";
            String subRtspUrl = buildRealRtspUrl(ip, cameraConfig.getSubstreamPath()) + "#backchannel=0#transport=tcp#timeout=30";
            registerStreamWithGo2rtc(streamName, rtspUrl);
            registerStreamWithGo2rtc("front_porch", rtspUrl);
            registerStreamWithGo2rtc(streamName + "_sub", subRtspUrl);
            registerStreamWithGo2rtc("front_porch_sub", subRtspUrl);
            return;
        }

        for (DeviceEntity dev : cameraDevices) {
            String streamName = resolveStreamName(dev.getId());
            String ip = dev.getIpAddress() != null ? dev.getIpAddress() : cameraConfig.getDefaultIp();
            String rtspUrl = buildRealRtspUrl(ip, cameraConfig.getMainstreamPath()) + "#backchannel=0#transport=tcp#timeout=30";
            String subRtspUrl = buildRealRtspUrl(ip, cameraConfig.getSubstreamPath()) + "#backchannel=0#transport=tcp#timeout=30";
            registerStreamWithGo2rtc(streamName, rtspUrl);
            registerStreamWithGo2rtc(streamName + "_sub", subRtspUrl);
            if (!streamName.equals(dev.getId())) {
                registerStreamWithGo2rtc(dev.getId(), rtspUrl);
                registerStreamWithGo2rtc(dev.getId() + "_sub", subRtspUrl);
            }
        }
    }

    /**
     * Dynamically registers or updates a stream source in go2rtc via PUT /api/streams with JSON body.
     */
    public boolean registerStreamWithGo2rtc(String streamName, String rtspUrl) {
        try {
            String go2rtcBase = cameraConfig.getGo2rtcUrl();
            if (go2rtcBase == null || go2rtcBase.isBlank()) {
                go2rtcBase = "http://localhost:1984";
            }
            URI uri = URI.create(go2rtcBase + "/api/streams");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);

            // Register both the raw RTSP source (for ultra-fast WebRTC) and the FFmpeg AAC audio pipeline (for HLS)
            String ffmpegTranscodeUrl = "ffmpeg:" + rtspUrl + "#video=copy#audio=aac";
            String jsonPayload = String.format("{\"%s\":[\"%s\",\"%s\"]}", streamName, rtspUrl, ffmpegTranscodeUrl);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                log.info("Dynamically registered camera stream [{}] in go2rtc with audio support", streamName);
                return true;
            } else {
                log.warn("go2rtc stream registration returned HTTP {} for [{}]", code, streamName);
            }
        } catch (Exception e) {
            log.debug("go2rtc stream registration deferred (go2rtc unreachable or initializing): {}", e.getMessage());
        }
        return false;
    }

    /**
     * Ensures the camera stream is registered in go2rtc before serving live feeds.
     */
    public void ensureCameraStreamRegistered(String entityOrDeviceId) {
        String streamName = resolveStreamName(entityOrDeviceId);
        String deviceId = entityOrDeviceId.startsWith("camera.") ? entityOrDeviceId.substring(7) : entityOrDeviceId;
        Optional<DeviceEntity> devOpt = deviceRepository.findById(deviceId);
        String ip = devOpt.map(DeviceEntity::getIpAddress).orElse(cameraConfig.getDefaultIp());
        String rtspUrl = buildRealRtspUrl(ip, cameraConfig.getMainstreamPath()) + "#backchannel=0#transport=tcp#timeout=30";
        registerStreamWithGo2rtc(streamName, rtspUrl);
    }

    /**
     * Retrieve instant snapshot frame directly from go2rtc RTSP bridge cache (< 20ms).
     */
    public byte[] fetchGo2rtcSnapshot(String deviceOrEntityId) {
        try {
            String go2rtcBase = cameraConfig.getGo2rtcUrl();
            if (go2rtcBase == null || go2rtcBase.isBlank()) {
                go2rtcBase = "http://localhost:1984";
            }
            String streamName = resolveStreamName(deviceOrEntityId);
            String urlStr = go2rtcBase + "/api/frame.jpeg?src=" + java.net.URLEncoder.encode(streamName, java.nio.charset.StandardCharsets.UTF_8);
            URI uri = URI.create(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream()) {
                    byte[] data = in.readAllBytes();
                    if (data.length > 1000) {
                        return data;
                    }
                }
            }
        } catch (Exception ignored) {
            // go2rtc not running or stream initializing
        }
        return null;
    }

    /**
     * Fetches raw HLS playlist (.m3u8) from go2rtc for the given stream name and optional query params.
     */
    public String fetchHlsPlaylist(String streamName) throws IOException {
        return fetchHlsPlaylist(streamName, null);
    }

    public String fetchHlsPlaylist(String streamName, String extraQuery) throws IOException {
        String go2rtcBase = cameraConfig.getGo2rtcUrl();
        if (go2rtcBase == null || go2rtcBase.isBlank()) {
            go2rtcBase = "http://localhost:1984";
        }
        String urlStr = go2rtcBase + "/api/stream.m3u8?src=" + java.net.URLEncoder.encode(streamName, java.nio.charset.StandardCharsets.UTF_8)
                + (extraQuery != null && !extraQuery.isBlank() ? "&" + extraQuery : "");
        URI uri = URI.create(urlStr);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code == 200) {
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IOException("go2rtc stream.m3u8 returned HTTP " + code);
    }

    /**
     * Fetches raw HLS video segment chunk (.ts / fMP4) from go2rtc.
     * Properly resolves segment paths to go2rtc's /api/hls/ handler.
     */
    public byte[] fetchHlsSegment(String segmentPath) throws IOException {
        String go2rtcBase = cameraConfig.getGo2rtcUrl();
        if (go2rtcBase == null || go2rtcBase.isBlank()) {
            go2rtcBase = "http://localhost:1984";
        }
        String clean = segmentPath.startsWith("/") ? segmentPath.substring(1) : segmentPath;
        String targetUrl;
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            targetUrl = clean;
        } else if (clean.startsWith("api/hls/")) {
            targetUrl = go2rtcBase + "/" + clean;
        } else if (clean.startsWith("hls/")) {
            targetUrl = go2rtcBase + "/api/" + clean;
        } else if (clean.startsWith("api/")) {
            targetUrl = go2rtcBase + "/" + clean;
        } else {
            // Segments in go2rtc (segment.ts, init.mp4, segment.m4s, etc.) live under /api/hls/
            targetUrl = go2rtcBase + "/api/hls/" + clean;
        }
        URI uri = URI.create(targetUrl);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(6000);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code == 200) {
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        }
        throw new IOException("go2rtc segment returned HTTP " + code + " for " + targetUrl);
    }

    /**
     * Retrieve latest snapshot frame from the camera.
     * Tries go2rtc first (< 25ms, no CPU load), then falls back to memory cache or visual placeholder.
     */
    public byte[] fetchSnapshot(String deviceId) {
        // 1. Try instantaneous go2rtc frame grab directly from active RTSP stream
        byte[] go2rtcFrame = fetchGo2rtcSnapshot(deviceId);
        if (go2rtcFrame != null && go2rtcFrame.length > 1000) {
            latestCameraFrame = go2rtcFrame;
            snapshotCache.put(deviceId, go2rtcFrame);
            return go2rtcFrame;
        }

        // 2. Serve warm frame directly from memory (< 1ms latency)
        if (latestCameraFrame != null && latestCameraFrame.length > 1000) {
            return latestCameraFrame;
        }

        byte[] cached = snapshotCache.get(deviceId);
        if (cached != null && cached.length > 1000) {
            return cached;
        }

        Optional<DeviceEntity> devOpt = deviceRepository.findById(deviceId);
        String ip = devOpt.map(DeviceEntity::getIpAddress).orElse(cameraConfig.getDefaultIp());

        // 3. Fallback: cached frame if available
        cached = snapshotCache.get(deviceId);
        if (cached != null) {
            return cached;
        }

        // 4. Fallback: generate a styled visual frame with live timestamps and status
        byte[] frame = generatePlaceholderSnapshot(deviceId, ip, devOpt.map(DeviceEntity::isOnline).orElse(false));
        snapshotCache.put(deviceId, frame);
        return frame;
    }

    private byte[] fetchHttpSnapshot(String ip, int port, String path) throws Exception {
        String urlStr = "http://" + ip + ":" + port + path;
        URI uri = URI.create(urlStr);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(3000);

        if (cameraConfig.getUsername() != null && !cameraConfig.getUsername().isBlank()) {
            String auth = cameraConfig.getUsername() + ":" + cameraConfig.getPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
        }

        int code = conn.getResponseCode();
        if (code == 200) {
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        }
        return null;
    }

    /**
     * Handles incoming motion trigger events from the camera (ONVIF PullPoint or HTTP Webhook).
     */
    public void handleMotionDetected(String deviceId, boolean detected) {
        log.info("Camera [{}] motion event detected: {}", deviceId, detected);

        // Resolve motion entity ID
        String motionEntityId = "binary_sensor." + deviceId + "_motion";
        Optional<EntityStateEntity> entityOpt = entityRepository.findByDeviceId(deviceId).stream()
                .filter(e -> "binary_sensor".equalsIgnoreCase(e.getDomain()))
                .findFirst();

        if (entityOpt.isPresent()) {
            motionEntityId = entityOpt.get().getId();
        }

        String state = detected ? "ON" : "OFF";
        Map<String, Object> attrs = Map.of(
                "device_class", "motion",
                "protocol", "CAMERA",
                "timestamp", LocalDateTime.now().toString()
        );

        stateEngineService.updateState(motionEntityId, state, attrs);

        // If motion is ON, schedule automatic reset to OFF after motionResetSeconds
        if (detected) {
            String finalEntityId = motionEntityId;
            scheduler.schedule(() -> {
                log.debug("Auto-resetting camera motion sensor [{}] to OFF", finalEntityId);
                stateEngineService.updateState(finalEntityId, "OFF", Map.of("device_class", "motion", "protocol", "CAMERA"));
            }, cameraConfig.getMotionResetSeconds(), TimeUnit.SECONDS);
        }
    }

    /**
     * Probes IP and port for reachability over TCP (e.g. RTSP port 554 or HTTP port 80).
     */
    public boolean checkReachability(String ip, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Periodic reachability check on all CAMERA devices.
     */
    @Scheduled(fixedDelayString = "${camera.poll-interval-ms:15000}")
    public void pollCameraAvailability() {
        if (!cameraConfig.isEnabled()) {
            return;
        }

        List<DeviceEntity> cameraDevices = deviceRepository.findAll().stream()
                .filter(d -> "CAMERA".equalsIgnoreCase(d.getProtocol()))
                .toList();

        for (DeviceEntity dev : cameraDevices) {
            String ip = dev.getIpAddress();
            if (ip == null || ip.isBlank()) {
                continue;
            }

            boolean online = checkReachability(ip, cameraConfig.getRtspPort(), 1500);
            if (!online) {
                online = checkReachability(ip, cameraConfig.getHttpPort(), 1500);
            }

            if (dev.isOnline() != online) {
                dev.setOnline(online);
                dev.setLastSeen(LocalDateTime.now());
                deviceRepository.save(dev);
                log.info("Camera [{}] reachability state changed: online={}", dev.getId(), online);

                // Update entity states
                List<EntityStateEntity> entities = entityRepository.findByDeviceId(dev.getId());
                for (EntityStateEntity e : entities) {
                    String state = online ? ("camera".equals(e.getDomain()) ? "idle" : "OFF") : "unavailable";
                    stateEngineService.updateState(e.getId(), state, Map.of("available", online, "protocol", "CAMERA"));
                }
            }
        }
    }

    /**
     * Scans a target subnet (e.g., IoT VLAN 192.168.20.0/24) or probes default camera IP to auto-discover cameras.
     */
    public List<Map<String, Object>> scanForCameras(String ipOrSubnet) {
        List<Map<String, Object>> discovered = new ArrayList<>();
        String targetIp = (ipOrSubnet != null && !ipOrSubnet.contains("/")) ? ipOrSubnet : cameraConfig.getDefaultIp();

        boolean rtspOpen = checkReachability(targetIp, cameraConfig.getRtspPort(), 1500);
        boolean httpOpen = checkReachability(targetIp, cameraConfig.getHttpPort(), 1500);

        if (rtspOpen || httpOpen) {
            String devId = "cpplus_" + targetIp.replace(".", "_");
            String devName = "CP Plus Camera (" + targetIp + ")";

            // Auto-register device if missing
            DeviceEntity dev = deviceRepository.findById(devId).orElseGet(() -> {
                DeviceEntity d = new DeviceEntity();
                d.setId(devId);
                d.setName(devName);
                d.setProtocol("CAMERA");
                d.setIpAddress(targetIp);
                d.setOnline(true);
                d.setFirmwareVersion("CP-EzyKam-v2.1");
                return deviceRepository.save(d);
            });

            // Auto-register camera and motion entities
            String camEntityId = "camera." + devId;
            if (entityRepository.findById(camEntityId).isEmpty()) {
                EntityStateEntity camEntity = new EntityStateEntity();
                camEntity.setId(camEntityId);
                camEntity.setName(devName);
                camEntity.setDomain("camera");
                camEntity.setDeviceId(devId);
                camEntity.setCurrentState("idle");
                camEntity.setAttributesJson("{\"stream_url\":\"" + getRtspSubstreamUrl(targetIp) + "\"}");
                entityRepository.save(camEntity);
                stateEngineService.updateState(camEntityId, "idle", Map.of("stream_url", getRtspSubstreamUrl(targetIp)));
            }

            String motionEntityId = "binary_sensor." + devId + "_motion";
            if (entityRepository.findById(motionEntityId).isEmpty()) {
                EntityStateEntity motionEntity = new EntityStateEntity();
                motionEntity.setId(motionEntityId);
                motionEntity.setName(devName + " Motion");
                motionEntity.setDomain("binary_sensor");
                motionEntity.setDeviceId(devId);
                motionEntity.setCurrentState("OFF");
                motionEntity.setAttributesJson("{\"device_class\":\"motion\"}");
                entityRepository.save(motionEntity);
                stateEngineService.updateState(motionEntityId, "OFF", Map.of("device_class", "motion"));
            }

            discovered.add(Map.of(
                    "deviceId", devId,
                    "name", devName,
                    "ip", targetIp,
                    "rtsp_open", rtspOpen,
                    "http_open", httpOpen,
                    "substream_url", getRtspSubstreamUrl(targetIp),
                    "mainstream_url", getRtspMainstreamUrl(targetIp)
            ));
        }

        return discovered;
    }

    /**
     * MASKED URL for API responses / logs / UI display only. Never pass this to ffmpeg or
     * any real connection attempt - the password is deliberately replaced with "***".
     */
    public String getRtspSubstreamUrl(String ip) {
        return "rtsp://" + cameraConfig.getUsername() + ":***@" + ip + ":" + cameraConfig.getRtspPort() + cameraConfig.getSubstreamPath();
    }

    public String getRtspMainstreamUrl(String ip) {
        return "rtsp://" + cameraConfig.getUsername() + ":***@" + ip + ":" + cameraConfig.getRtspPort() + cameraConfig.getMainstreamPath();
    }

    /**
     * REAL, connection-ready URL with the actual credentials, properly percent-encoded so
     * special characters in the username/password (e.g. '@', ':') don't break URL parsing.
     * Use this - never the masked getRtsp*Url() methods above - for anything that actually
     * connects to the camera (ffmpeg, PTZ probes, etc).
     */
    private String buildRealRtspUrl(String ip, String path) {
        String user = urlEncode(cameraConfig.getUsername());
        String pass = urlEncode(cameraConfig.getPassword());
        return "rtsp://" + user + ":" + pass + "@" + ip + ":" + cameraConfig.getRtspPort() + path;
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20"); // credentials use %20 for spaces, not '+'
    }

    public Map<String, Object> getAdapterStatus() {
        long cameraCount = deviceRepository.findAll().stream()
                .filter(d -> "CAMERA".equalsIgnoreCase(d.getProtocol()))
                .count();

        long onlineCount = deviceRepository.findAll().stream()
                .filter(d -> "CAMERA".equalsIgnoreCase(d.getProtocol()) && d.isOnline())
                .count();

        return Map.of(
                "protocol", "CAMERA",
                "enabled", cameraConfig.isEnabled(),
                "default_ip", cameraConfig.getDefaultIp(),
                "rtsp_port", cameraConfig.getRtspPort(),
                "http_port", cameraConfig.getHttpPort(),
                "subnet", cameraConfig.getSubnet(),
                "total_cameras", cameraCount,
                "online_cameras", onlineCount
        );
    }

    /**
     * Generates an image frame for dashboard visual display.
     */
    private byte[] generatePlaceholderSnapshot(String deviceId, String ip, boolean online) {
        int width = 640;
        int height = 360;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Dark gradient background
        GradientPaint gradient = new GradientPaint(0, 0, new Color(15, 23, 42), width, height, new Color(30, 41, 59));
        g.setPaint(gradient);
        g.fillRect(0, 0, width, height);

        // Viewport crosshair grid lines
        g.setColor(new Color(51, 65, 85, 120));
        g.drawRect(20, 20, width - 40, height - 40);
        g.drawLine(width / 2 - 15, height / 2, width / 2 + 15, height / 2);
        g.drawLine(width / 2, height / 2 - 15, width / 2, height / 2 + 15);

        // Live REC badge
        g.setColor(online ? new Color(239, 68, 68) : new Color(100, 116, 139));
        g.fillOval(35, 35, 12, 12);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString(online ? "LIVE • CP PLUS" : "STANDBY • CP PLUS", 55, 46);

        // Timestamp overlay
        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        g.setFont(new Font("Monospaced", Font.BOLD, 13));
        g.setColor(new Color(226, 232, 240));
        g.drawString(nowStr, width - 195, 46);

        // Center status message
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.setColor(online ? new Color(56, 189, 248) : new Color(148, 163, 184));
        String title = online ? "CP PLUS WI-FI STREAM READY" : "CAMERA INITIALIZING / OFFLINE";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, (width - fm.stringWidth(title)) / 2, height / 2 - 10);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(148, 163, 184));
        String sub = "IP: " + (ip != null ? ip : "Unassigned") + " | RTSP Port: " + cameraConfig.getRtspPort() + " | IoT VLAN";
        FontMetrics subFm = g.getFontMetrics();
        g.drawString(sub, (width - subFm.stringWidth(sub)) / 2, height / 2 + 20);

        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "jpeg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}