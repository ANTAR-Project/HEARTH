/**
 * ANTAR OS • IoT Hub & Live Camera Client
 * Handles WebRTC WHEP, HLS Adaptive Streaming, PTZ controls, TrueNAS clip recording, and WebSocket real-time telemetry.
 */

let ws;
let reconnectTimer;
let activeStreamEngine = 'webrtc'; // 'webrtc' | 'hls'
let activeHls = null;
let activePeerConnection = null;

const CAMERA_ENTITY = 'camera.front_porch_cam';

// --- WebSocket Real-Time State Engine ---
function initWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/states`;
    const statusBadge = document.getElementById('ws-status-badge');
    const statusText = document.getElementById('ws-status-text');

    try {
        ws = new WebSocket(wsUrl);

        ws.onopen = () => {
            console.log('[WS] Connected to ANTAR State Engine');
            if (statusText) statusText.textContent = 'Connected';
            if (statusBadge) statusBadge.style.borderColor = 'rgba(16, 185, 129, 0.4)';
            clearTimeout(reconnectTimer);
        };

        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                handleWebSocketMessage(data);
            } catch (err) {
                console.error('[WS] JSON parse error:', err);
            }
        };

        ws.onclose = () => {
            if (statusText) statusText.textContent = 'Reconnecting...';
            if (statusBadge) statusBadge.style.borderColor = 'rgba(245, 158, 11, 0.4)';
            reconnectTimer = setTimeout(initWebSocket, 3000);
        };

        ws.onerror = (err) => {
            console.error('[WS] Socket error:', err);
        };
    } catch (e) {
        console.error('[WS] Init failed:', e);
        reconnectTimer = setTimeout(initWebSocket, 4000);
    }
}

function handleWebSocketMessage(msg) {
    if (msg.type === 'state_changed') {
        const entityId = msg.entityId;
        const state = (msg.newState && msg.newState.state) ? msg.newState.state.toUpperCase() : 'OFF';

        if (entityId === 'binary_sensor.front_porch_cam_motion') {
            updateMotionSensorUI(state);
        }
    }
}

function updateMotionSensorUI(state) {
    const isMotion = (state === 'ON' || state === 'DETECTED');
    const motionTag = document.getElementById('cam-motion-tag');
    const motionPillIcon = document.getElementById('motion-pill-icon');
    const motionPillStatus = document.getElementById('motion-pill-status');
    const motionPillBadge = document.getElementById('motion-pill-badge');

    if (motionTag) {
        motionTag.textContent = isMotion ? 'Motion: DETECTED' : 'Motion: Idle';
        motionTag.style.background = isMotion ? 'rgba(244, 63, 94, 0.85)' : 'rgba(0, 0, 0, 0.7)';
        motionTag.style.borderColor = isMotion ? 'var(--accent-rose)' : 'rgba(255, 255, 255, 0.15)';
    }

    if (motionPillStatus) motionPillStatus.textContent = isMotion ? 'DETECTED' : 'Idle';
    if (motionPillIcon) motionPillIcon.textContent = isMotion ? '🚨' : '🟢';

    if (motionPillBadge) {
        if (isMotion) {
            motionPillBadge.className = 'pill-badge rose';
            motionPillBadge.style.borderColor = 'rgba(244, 63, 94, 0.5)';
            motionPillBadge.style.color = 'var(--accent-rose)';
            showToast('🚨 Motion detected on Front Porch Camera!', 'error');
        } else {
            motionPillBadge.className = 'pill-badge emerald';
            motionPillBadge.style.borderColor = '';
            motionPillBadge.style.color = '';
        }
    }
}

// --- Live Camera Streaming Engine (WebRTC WHEP & HLS) ---
function switchStreamEngine(engine) {
    activeStreamEngine = engine;
    const btnWebrtc = document.getElementById('btn-toggle-webrtc');
    const btnHls = document.getElementById('btn-toggle-hls');
    const badge = document.getElementById('stream-engine-badge');

    if (engine === 'webrtc') {
        if (btnWebrtc) btnWebrtc.classList.add('active');
        if (btnHls) btnHls.classList.remove('active');
        if (badge) badge.textContent = '⚡ WebRTC WHEP (Ultra-Low Latency)';
        startWebRtcStream();
    } else {
        if (btnHls) btnHls.classList.add('active');
        if (btnWebrtc) btnWebrtc.classList.remove('active');
        if (badge) badge.textContent = '📺 HLS Adaptive Stream';
        startHlsStream();
    }
}

async function startWebRtcStream() {
    stopHlsStream();
    stopWebRtcStream();

    const video = document.getElementById('camera-live-video');
    const img = document.getElementById('camera-snapshot-view');
    if (!video) return;

    try {
        const pc = new RTCPeerConnection({
            iceServers: [
                { urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] }
            ]
        });

        pc.addTransceiver('video', { direction: 'recvonly' });
        pc.addTransceiver('audio', { direction: 'recvonly' });

        pc.ontrack = (event) => {
            if (video && event.streams && event.streams[0]) {
                video.srcObject = event.streams[0];
            }
        };

        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);

        // Wait up to 600ms for candidate gathering
        await new Promise((resolve) => {
            if (pc.iceGatheringState === 'complete') resolve();
            else {
                const check = () => {
                    if (pc.iceGatheringState === 'complete') {
                        pc.removeEventListener('icegatheringstatechange', check);
                        resolve();
                    }
                };
                pc.addEventListener('icegatheringstatechange', check);
                setTimeout(resolve, 600);
            }
        });

        // Forward SDP offer to Spring Boot WHEP endpoint
        const res = await fetch(`/api/camera/${CAMERA_ENTITY}/webrtc`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/sdp' },
            body: pc.localDescription.sdp
        });

        if (!res.ok) {
            throw new Error(`WebRTC signaling error (HTTP ${res.status})`);
        }

        const answerSdp = await res.text();
        await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });

        activePeerConnection = pc;

        if (img) img.style.display = 'none';
        video.style.display = 'block';
        video.muted = true; // start muted for autoplay compliance
        video.play().catch(() => {});
        showToast('⚡ WebRTC connected: Ultra-low latency stream active');
    } catch (err) {
        console.warn('WebRTC start failed, falling back to HLS:', err);
        showToast('WebRTC failed: falling back to HLS stream...', 'error');
        switchStreamEngine('hls');
    }
}

function stopWebRtcStream() {
    if (activePeerConnection) {
        activePeerConnection.close();
        activePeerConnection = null;
    }
    const video = document.getElementById('camera-live-video');
    if (video) {
        video.srcObject = null;
    }
}

function startHlsStream() {
    stopWebRtcStream();
    stopHlsStream();

    const video = document.getElementById('camera-live-video');
    const img = document.getElementById('camera-snapshot-view');
    if (!video) return;

    const streamUrl = `/api/camera/${CAMERA_ENTITY}/live/index.m3u8`;

    if (window.Hls && Hls.isSupported()) {
        const hls = new Hls({
            enableWorker: true,
            lowLatencyMode: true,
            liveSyncDuration: 2,
            liveMaxLatencyDuration: 6
        });
        hls.loadSource(streamUrl);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
            video.muted = true;
            video.play().catch(() => {});
        });
        hls.on(Hls.Events.ERROR, (event, data) => {
            if (data.fatal) {
                console.warn('HLS stream fatal error:', data);
                hls.destroy();
                activeHls = null;
            }
        });
        activeHls = hls;
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = streamUrl;
        video.play().catch(() => {});
    }

    if (img) img.style.display = 'none';
    video.style.display = 'block';
    showToast('📺 HLS stream connected');
}

function stopHlsStream() {
    if (activeHls) {
        activeHls.destroy();
        activeHls = null;
    }
    const video = document.getElementById('camera-live-video');
    if (video) {
        video.pause();
        video.removeAttribute('src');
    }
}

// --- Snapshot Refresh ---
function refreshCameraSnapshot(entityId) {
    const video = document.getElementById('camera-live-video');
    const img = document.getElementById('camera-snapshot-view');
    if (!img) return;

    const timestamp = new Date().getTime();
    img.src = `/api/camera/${entityId}/snapshot?t=${timestamp}`;
    img.style.display = 'block';
    if (video) video.style.display = 'none';

    // Return to live stream after 4 seconds
    setTimeout(() => {
        if (img) img.style.display = 'none';
        if (video) video.style.display = 'block';
    }, 4000);

    showToast('🔄 Snapshot refreshed');
}

// --- PTZ Control ---
async function sendPtz(command) {
    try {
        const res = await fetch(`/api/camera/${CAMERA_ENTITY}/ptz`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: command, speed: 4 })
        });
        if (res.ok) {
            showToast(`🕹️ PTZ Command: ${command.replace('PTZ_', '')}`);
        } else {
            showToast('PTZ command failed', 'error');
        }
    } catch (err) {
        showToast('PTZ error: ' + err.message, 'error');
    }
}

// --- TrueNAS Video Clip Recording ---
async function recordCameraClip(entityId) {
    const durationSelect = document.getElementById('clip-duration-select');
    const duration = durationSelect ? parseInt(durationSelect.value, 10) || 15 : 15;
    const btn = document.getElementById('btn-record-clip');
    const progressBar = document.getElementById('recording-progress-bar');

    if (btn) {
        btn.disabled = true;
        btn.textContent = `⏳ Recording (${duration}s)...`;
    }
    if (progressBar) {
        progressBar.style.display = 'block';
        progressBar.textContent = `⏳ Capturing ${duration}s clip and archiving directly to TrueNAS SMB...`;
    }

    showToast(`🔴 Recording ${duration}s clip from camera to TrueNAS...`);

    try {
        const res = await fetch(`/api/camera/${entityId}/record?duration=${duration}`, {
            method: 'POST'
        });

        const data = await res.json();
        if (res.ok && data.status === 'success') {
            showToast(`✅ Saved to TrueNAS! ID: ${data.recording.id}`, 'success');
            if (progressBar) {
                progressBar.innerHTML = `✅ Successfully stored in TrueNAS Vault! <a href="/nas" style="color: white; text-decoration: underline; margin-left: 0.5rem;">Open Vault &rarr;</a>`;
                setTimeout(() => { progressBar.style.display = 'none'; }, 7000);
            }
        } else {
            throw new Error(data.message || 'Recording failed');
        }
    } catch (err) {
        console.error('Recording error:', err);
        showToast('❌ Recording error: ' + err.message, 'error');
        if (progressBar) {
            progressBar.textContent = '❌ Recording failed: ' + err.message;
        }
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = '🔴 Record to TrueNAS';
        }
    }
}

// --- Toast Notifications ---
function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;

    container.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(10px)';
        toast.style.transition = 'all 0.2s';
        setTimeout(() => toast.remove(), 200);
    }, 4000);
}

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    initWebSocket();
    switchStreamEngine('webrtc');
});
