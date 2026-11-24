/**
 * TrueNAS Storage Vault Client JavaScript
 * Manages Camera Vault, Media Vault, File Uploads, PDF / Image / Video Viewers, and Deletions.
 */

let currentVaultTab = 'camera';
let pendingDelete = null; // { type: 'camera' | 'media', path: string }
let modalHls = null;

// --- Tab Switching ---
function switchVaultTab(tab) {
    currentVaultTab = tab;
    const btnCam = document.getElementById('tab-btn-camera');
    const btnMedia = document.getElementById('tab-btn-media');
    const panelCam = document.getElementById('vault-panel-camera');
    const panelMedia = document.getElementById('vault-panel-media');

    if (tab === 'camera') {
        btnCam.classList.add('active');
        btnMedia.classList.remove('active');
        panelCam.style.display = 'block';
        panelMedia.style.display = 'none';
        loadCameraVault();
    } else {
        btnMedia.classList.add('active');
        btnCam.classList.remove('active');
        panelCam.style.display = 'none';
        panelMedia.style.display = 'block';
        loadMediaVault();
    }
}

// --- 1. Camera Surveillance Vault ---
async function loadCameraVault() {
    const tbody = document.getElementById('camera-vault-body');
    if (!tbody) return;

    try {
        const res = await fetch('/api/camera/recordings');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const list = await res.json();

        if (!list || list.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="6" style="padding: 2.5rem; text-align: center; color: var(--text-muted);">
                        No camera recordings in TrueNAS SMB vault yet. Record a clip from the <a href="/iot" style="color: var(--accent-primary);">IoT Hub</a>.
                    </td>
                </tr>`;
            return;
        }

        tbody.innerHTML = list.map(item => {
            const isEncoded = item.status === 'ENCODED';
            return `
            <tr>
                <td style="font-family: 'JetBrains Mono', monospace; font-size: 0.82rem; color: var(--accent-primary);">
                    📹 ${escapeHtml(item.id)}
                </td>
                <td style="color: var(--text-main); font-weight: 500;">
                    ${escapeHtml(item.filename)}
                </td>
                <td style="color: var(--text-muted); font-size: 0.82rem;">
                    ${escapeHtml(item.formattedSize || item.sizeBytes + ' B')}
                </td>
                <td style="color: var(--text-muted); font-size: 0.82rem;">
                    ${escapeHtml(item.timestamp || '—')}
                </td>
                <td>
                    <span class="pill-badge ${isEncoded ? 'emerald' : 'cyan'}">
                        ${isEncoded ? '⚡ HLS & RAW READY' : '💾 RAW ARCHIVED'}
                    </span>
                </td>
                <td style="text-align: right;">
                    <div style="display: inline-flex; gap: 0.4rem;">
                        <button class="btn-cam" style="padding: 0.3rem 0.65rem; font-size: 0.76rem;"
                            onclick="playCameraClip('${item.id}', '${escapeHtml(item.filename)}', '${isEncoded ? item.streamUrl : ''}', '${item.previewUrl}', ${isEncoded})">
                            ▶ Play
                        </button>
                        <a class="btn-cam" style="padding: 0.3rem 0.65rem; font-size: 0.76rem;"
                            href="${item.downloadUrl}" download>
                            ⬇ Download
                        </a>
                        <button class="btn-cam" style="padding: 0.3rem 0.65rem; font-size: 0.76rem; color: var(--accent-rose); border-color: rgba(244, 63, 94, 0.3);"
                            onclick="promptDelete('camera', '${item.id}', '${escapeHtml(item.filename)}')">
                            🗑 Delete
                        </button>
                    </div>
                </td>
            </tr>`;
        }).join('');
    } catch (err) {
        tbody.innerHTML = `
            <tr>
                <td colspan="6" style="padding: 2rem; text-align: center; color: var(--accent-rose);">
                    Failed to load TrueNAS camera vault: ${escapeHtml(err.message)}
                </td>
            </tr>`;
    }
}

function playCameraClip(id, filename, streamUrl, previewUrl, isEncoded) {
    const title = 'Surveillance: ' + filename;
    const meta = isEncoded
        ? `TrueNAS ZFS Storage • ID: ${id} • ⚡ Adaptive Multi-Bitrate HLS`
        : `TrueNAS ZFS Storage • ID: ${id} • 💾 Direct MP4 Archival Preview`;

    openVideoModal(title, meta, isEncoded ? streamUrl : null, previewUrl, `/api/camera/recordings/${id}/download`);
}

// --- 2. Media & Documents Vault ---
let mediaPollTimer = null;

async function loadMediaVault() {
    const tbody = document.getElementById('media-vault-body');
    if (!tbody) return;

    if (mediaPollTimer) {
        clearTimeout(mediaPollTimer);
        mediaPollTimer = null;
    }

    try {
        const res = await fetch('/api/nas/files?folder=media');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const list = await res.json();

        if (!list || list.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="5" style="padding: 2.5rem; text-align: center; color: var(--text-muted);">
                        The <code>media/</code> folder is empty. Drag and drop files above to store images, PDFs, documents, or videos on TrueNAS.
                    </td>
                </tr>`;
            return;
        }

        // Auto-poll if any video is currently being encoded by Kafka/encoding-service
        const hasProcessing = list.some(item => item.fileType === 'video' && item.status === 'PROCESSING');
        if (hasProcessing) {
            mediaPollTimer = setTimeout(() => {
                if (currentVaultTab === 'media') loadMediaVault();
            }, 7000);
        }

        tbody.innerHTML = list.map(item => {
            const iconBadge = getFileIconBadge(item.fileType, item.extension);
            const isVideo = item.fileType === 'video';
            const isEncoded = item.status === 'ENCODED';
            return `
            <tr>
                <td style="color: var(--text-main); font-weight: 500;">
                    <div style="display: flex; align-items: center; min-width: 0;">
                        ${iconBadge}
                        <span style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 220px;" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</span>
                    </div>
                </td>
                <td>
                    <span class="pill-badge" style="text-transform: uppercase;">
                        ${escapeHtml(item.fileType)} (${escapeHtml(item.extension)})
                    </span>
                    ${isVideo ? `
                        <span class="pill-badge ${isEncoded ? 'emerald' : 'indigo'}" style="margin-left: 0.35rem; font-size: 0.72rem;">
                            ${isEncoded ? '⚡ HLS READY' : '⏳ ENCODING'}
                        </span>` : ''}
                </td>
                <td style="color: var(--text-muted); font-size: 0.82rem;">
                    ${escapeHtml(item.formattedSize)}
                </td>
                <td style="color: var(--text-muted); font-size: 0.82rem;">
                    ${escapeHtml(item.lastModified)}
                </td>
                <td style="text-align: right;">
                    <div style="display: inline-flex; gap: 0.4rem;">
                        <button class="btn-cam primary" style="padding: 0.3rem 0.65rem; font-size: 0.76rem;"
                            onclick="previewMediaFile('${escapeHtml(item.name)}', '${escapeHtml(item.path)}', '${item.fileType}', '${item.previewUrl}', '${item.downloadUrl}', '${item.streamUrl || ''}', '${item.status || ''}')">
                            ${isVideo && isEncoded ? '⚡ Stream' : '👁 View'}
                        </button>
                        <a class="btn-cam" style="padding: 0.3rem 0.65rem; font-size: 0.76rem;"
                            href="${item.downloadUrl}" download>
                            ⬇ Download
                        </a>
                        <button class="btn-cam" style="padding: 0.3rem 0.65rem; font-size: 0.76rem; color: var(--accent-rose); border-color: rgba(244, 63, 94, 0.3);"
                            onclick="promptDelete('media', '${escapeHtml(item.path)}', '${escapeHtml(item.name)}')">
                            🗑 Delete
                        </button>
                    </div>
                </td>
            </tr>`;
        }).join('');
    } catch (err) {
        tbody.innerHTML = `
            <tr>
                <td colspan="5" style="padding: 2rem; text-align: center; color: var(--accent-rose);">
                    Failed to load media vault: ${escapeHtml(err.message)}
                </td>
            </tr>`;
    }
}

function getFileIconBadge(fileType, ext) {
    switch (fileType) {
        case 'pdf':
            return `<span class="file-icon-badge pdf">📄</span>`;
        case 'image':
            return `<span class="file-icon-badge image">🖼️</span>`;
        case 'video':
            return `<span class="file-icon-badge video">🎬</span>`;
        case 'document':
            return `<span class="file-icon-badge document">📝</span>`;
        default:
            return `<span class="file-icon-badge other">📁</span>`;
    }
}

function previewMediaFile(name, path, fileType, previewUrl, downloadUrl, streamUrl, status) {
    if (fileType === 'image') {
        openImageModal(name, previewUrl, downloadUrl);
    } else if (fileType === 'pdf') {
        openPdfModal(name, previewUrl, downloadUrl);
    } else if (fileType === 'video') {
        const isEncoded = status === 'ENCODED' && streamUrl;
        if (!isEncoded) {
            showToast('⏳ Video is currently being encoded for adaptive HLS streaming. Archival preview is loading...', 'info');
        }
        const meta = isEncoded
            ? `TrueNAS Media Storage • Path: ${path} • ⚡ Multi-Bitrate HLS Chunks`
            : `TrueNAS Media Storage • Path: ${path} • 💾 Direct Archival Preview`;
        openVideoModal(name, meta, isEncoded ? streamUrl : null, previewUrl, downloadUrl);
    } else {
        // Document or other file -> open in new browser tab
        window.open(previewUrl, '_blank');
    }
}

// --- 3. Drag & Drop and File Upload ---
function setupDropzone() {
    const dropzone = document.getElementById('media-dropzone');
    if (!dropzone) return;

    ['dragenter', 'dragover'].forEach(eventName => {
        dropzone.addEventListener(eventName, (e) => {
            e.preventDefault();
            dropzone.classList.add('dragover');
        });
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropzone.addEventListener(eventName, (e) => {
            e.preventDefault();
            dropzone.classList.remove('dragover');
        });
    });

    dropzone.addEventListener('drop', (e) => {
        const files = e.dataTransfer.files;
        if (files && files.length > 0) {
            uploadFile(files[0]);
        }
    });
}

function handleFileSelect(event) {
    const files = event.target.files;
    if (files && files.length > 0) {
        uploadFile(files[0]);
    }
}

function uploadFile(file) {
    const progressBox = document.getElementById('upload-progress-box');
    const progressBar = document.getElementById('upload-progress-bar');
    const progressText = document.getElementById('upload-progress-text');
    const autoEncode = document.getElementById('auto-encode-video-checkbox')?.checked || false;

    if (!file) return;

    if (file.size > 2 * 1024 * 1024 * 1024) {
        showToast(`❌ File "${file.name}" is too large (${(file.size / (1024 * 1024 * 1024)).toFixed(1)} GB). Maximum allowed upload size is 2 GB.`, 'error');
        return;
    }

    const formData = new FormData();
    formData.append('file', file);
    formData.append('folder', 'media');
    formData.append('autoEncode', autoEncode);

    progressBox.style.display = 'block';
    progressBar.style.width = '0%';
    progressText.textContent = `Uploading ${file.name} to TrueNAS...`;

    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/nas/upload');

    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            const percent = Math.round((e.loaded / e.total) * 100);
            progressBar.style.width = percent + '%';
            progressText.textContent = `Uploading ${file.name} to TrueNAS: ${percent}%`;
        }
    };

    xhr.onload = () => {
        progressBox.style.display = 'none';
        if (xhr.status >= 200 && xhr.status < 300) {
            showToast(`✅ "${file.name}" successfully stored in TrueNAS!`, 'success');
            loadMediaVault();
        } else {
            let errorMsg = 'HTTP ' + xhr.status;
            try {
                const errObj = JSON.parse(xhr.responseText);
                if (errObj.message) errorMsg = errObj.message;
            } catch (e) {
                if (xhr.responseText && xhr.responseText.length < 200) {
                    errorMsg = xhr.responseText;
                }
            }
            showToast(`❌ Upload failed: ${errorMsg}`, 'error');
        }
        // Reset file input
        const input = document.getElementById('media-file-input');
        if (input) input.value = '';
    };

    xhr.onerror = () => {
        progressBox.style.display = 'none';
        showToast('❌ Network error during upload to TrueNAS', 'error');
    };

    xhr.send(formData);
}

// --- 4. Modals System ---
function openVideoModal(title, meta, streamUrl, previewUrl, downloadUrl) {
    const modal = document.getElementById('video-modal');
    const titleEl = document.getElementById('video-modal-title');
    const metaEl = document.getElementById('video-modal-meta');
    const video = document.getElementById('modal-video-player');
    const downloadBtn = document.getElementById('video-modal-download');

    if (titleEl) titleEl.textContent = title;
    if (metaEl) metaEl.textContent = meta;
    if (downloadBtn) downloadBtn.href = downloadUrl;

    if (modalHls) {
        modalHls.destroy();
        modalHls = null;
    }

    if (streamUrl && window.Hls && Hls.isSupported()) {
        console.log("Initializing HLS stream from:", streamUrl);
        modalHls = new Hls({
            enableWorker: true,
            lowLatencyMode: false,
            fragLoadingTimeOut: 20000,
            manifestLoadingTimeOut: 20000
        });
        modalHls.loadSource(streamUrl);
        modalHls.attachMedia(video);
        modalHls.on(Hls.Events.MANIFEST_PARSED, () => {
            console.log("HLS manifest parsed, playing stream chunks...");
            video.play().catch(e => console.warn("Autoplay:", e));
        });
        modalHls.on(Hls.Events.ERROR, (event, data) => {
            if (data.fatal) {
                switch (data.type) {
                    case Hls.ErrorTypes.NETWORK_ERROR:
                        console.warn("HLS network error, attempting recovery...", data);
                        modalHls.startLoad();
                        break;
                    case Hls.ErrorTypes.MEDIA_ERROR:
                        console.warn("HLS media error, attempting recovery...", data);
                        modalHls.recoverMediaError();
                        break;
                    default:
                        console.error("Fatal HLS error, falling back to direct MP4 preview:", data);
                        modalHls.destroy();
                        modalHls = null;
                        if (previewUrl) {
                            showToast('⚠️ HLS stream unavailable, switching to preview', 'warning');
                            video.src = previewUrl;
                            video.play().catch(() => {});
                        }
                        break;
                }
            }
        });
    } else {
        video.src = previewUrl;
        video.play().catch(() => {});
    }

    modal.style.display = 'flex';
}

function closeVideoModal() {
    const modal = document.getElementById('video-modal');
    const video = document.getElementById('modal-video-player');
    if (video) {
        video.pause();
        video.removeAttribute('src');
        video.load();
    }
    if (modalHls) {
        modalHls.destroy();
        modalHls = null;
    }
    if (modal) modal.style.display = 'none';
}

function openImageModal(title, previewUrl, downloadUrl) {
    const modal = document.getElementById('image-modal');
    const titleEl = document.getElementById('image-modal-title');
    const img = document.getElementById('modal-image-preview');
    const downloadBtn = document.getElementById('image-modal-download');

    if (titleEl) titleEl.textContent = 'Image: ' + title;
    if (img) img.src = previewUrl;
    if (downloadBtn) downloadBtn.href = downloadUrl;

    modal.style.display = 'flex';
}

function closeImageModal() {
    const modal = document.getElementById('image-modal');
    const img = document.getElementById('modal-image-preview');
    if (img) img.src = '';
    if (modal) modal.style.display = 'none';
}

function openPdfModal(title, previewUrl, downloadUrl) {
    const modal = document.getElementById('pdf-modal');
    const titleEl = document.getElementById('pdf-modal-title');
    const frame = document.getElementById('modal-pdf-frame');
    const downloadBtn = document.getElementById('pdf-modal-download');

    if (titleEl) titleEl.textContent = 'Document: ' + title;
    if (frame) frame.src = previewUrl;
    if (downloadBtn) downloadBtn.href = downloadUrl;

    modal.style.display = 'flex';
}

function closePdfModal() {
    const modal = document.getElementById('pdf-modal');
    const frame = document.getElementById('modal-pdf-frame');
    if (frame) frame.src = 'about:blank';
    if (modal) modal.style.display = 'none';
}

// --- 5. Delete Handling ---
function promptDelete(type, idOrPath, displayName) {
    pendingDelete = { type, idOrPath };
    const targetLabel = document.getElementById('delete-target-path');
    if (targetLabel) {
        targetLabel.textContent = displayName || idOrPath;
    }
    const modal = document.getElementById('delete-modal');
    if (modal) modal.style.display = 'flex';
}

function closeDeleteModal() {
    pendingDelete = null;
    const modal = document.getElementById('delete-modal');
    if (modal) modal.style.display = 'none';
}

async function executeDelete() {
    if (!pendingDelete) return;

    const { type, idOrPath } = pendingDelete;
    closeDeleteModal();

    try {
        let res;
        if (type === 'camera') {
            res = await fetch(`/api/camera/recordings/${encodeURIComponent(idOrPath)}`, { method: 'DELETE' });
        } else {
            res = await fetch(`/api/nas/files?path=${encodeURIComponent(idOrPath)}`, { method: 'DELETE' });
        }

        if (res.ok) {
            showToast(`🗑 Successfully deleted from TrueNAS SMB!`, 'success');
            if (type === 'camera') loadCameraVault();
            else loadMediaVault();
        } else {
            showToast(`❌ Delete failed (HTTP ${res.status})`, 'error');
        }
    } catch (err) {
        showToast(`❌ Delete error: ${err.message}`, 'error');
    }
}

// --- 6. Utilities ---
function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

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

// Close modals on Escape key
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        closeVideoModal();
        closeImageModal();
        closePdfModal();
        closeDeleteModal();
    }
});

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    setupDropzone();
    loadCameraVault();
});
