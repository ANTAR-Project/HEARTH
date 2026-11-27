package com.antar.cameraorchestrator.grpc;

import com.antar.grpc.filetransfer.DownloadRequest;
import com.antar.grpc.filetransfer.FileChunk;
import com.antar.grpc.filetransfer.FileTransferGrpcServiceGrpc;
import com.antar.grpc.filetransfer.UploadResponse;
import com.antar.grpc.streaming.PlaylistRequest;
import com.antar.grpc.streaming.StreamingGrpcServiceGrpc;
import com.google.protobuf.ByteString;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class NasOrchestratorGrpcClient {
    private static final Logger log = LoggerFactory.getLogger(NasOrchestratorGrpcClient.class);

    private final FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferAsyncStub;
    private final FileTransferGrpcServiceGrpc.FileTransferGrpcServiceBlockingStub fileTransferBlockingStub;
    private final StreamingGrpcServiceGrpc.StreamingGrpcServiceBlockingStub streamingBlockingStub;

    public NasOrchestratorGrpcClient(
        FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferAsyncStub,
        FileTransferGrpcServiceGrpc.FileTransferGrpcServiceBlockingStub fileTransferBlockingStub,
        StreamingGrpcServiceGrpc.StreamingGrpcServiceBlockingStub streamingBlockingStub
    ) {
        this.fileTransferAsyncStub = fileTransferAsyncStub;
        this.fileTransferBlockingStub = fileTransferBlockingStub;
        this.streamingBlockingStub = streamingBlockingStub;
    }

    public String uploadFile(String destinationPath, File file) throws IOException {
        log.info("Starting gRPC file upload to NAS: destination={}, size={} bytes", destinationPath, file.length());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> responsePath = new AtomicReference<>(destinationPath);
        SynchronousQueue<Object> readySignal = new SynchronousQueue<>();
        AtomicReference<ClientCallStreamObserver<FileChunk>> callObserverRef = new AtomicReference<>();

        StreamObserver<FileChunk> requestObserver = fileTransferAsyncStub.uploadFile(
            new ClientResponseObserver<FileChunk, UploadResponse>() {
                @Override
                public void beforeStart(ClientCallStreamObserver<FileChunk> requestStream) {
                    callObserverRef.set(requestStream);
                    requestStream.setOnReadyHandler(() -> readySignal.offer(Boolean.TRUE));
                }

                @Override
                public void onNext(UploadResponse value) {
                    if (value.getPath() != null && !value.getPath().isBlank()) {
                        responsePath.set(value.getPath());
                    }
                    log.info("gRPC upload response for {}: {}", destinationPath, value.getMessage());
                }

                @Override
                public void onError(Throwable t) {
                    log.error("gRPC upload stream error for {}: {}", destinationPath, t.getMessage());
                    error.set(t);
                    readySignal.offer(Boolean.FALSE);
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    readySignal.offer(Boolean.TRUE);
                    latch.countDown();
                }
            }
        );

        ClientCallStreamObserver<FileChunk> clientCallObserver = callObserverRef.get();
        try (InputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            boolean first = true;
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                while (!clientCallObserver.isReady()) {
                    if (error.get() != null || latch.getCount() == 0) break;
                    try {
                        Object signal = readySignal.poll(30, TimeUnit.SECONDS);
                        if (signal == null) {
                            requestObserver.onError(new IOException("Timed out waiting for gRPC backpressure relief"));
                            throw new IOException("gRPC upload timed out waiting for channel ready");
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Upload interrupted while waiting for gRPC channel", ie);
                    }
                }
                if (error.get() != null) {
                    throw new IOException("gRPC upload stream error", error.get());
                }

                FileChunk.Builder chunk = FileChunk.newBuilder()
                    .setContent(ByteString.copyFrom(buffer, 0, bytesRead));
                if (first) {
                    chunk.setDestinationPath(destinationPath);
                    first = false;
                }
                requestObserver.onNext(chunk.build());
            }

            requestObserver.onCompleted();
            if (!latch.await(120, TimeUnit.SECONDS)) {
                throw new IOException("gRPC upload timed out awaiting server completion");
            }
            if (error.get() != null) {
                throw new IOException("gRPC upload failed", error.get());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted", ie);
        }

        return responsePath.get();
    }

    public void downloadToResponse(
        String nasPath,
        HttpServletResponse response,
        String contentType,
        String contentDisposition
    ) throws IOException {
        response.setContentType(contentType);
        if (contentDisposition != null) {
            response.setHeader("Content-Disposition", contentDisposition);
        }

        Iterator<FileChunk> chunks = fileTransferBlockingStub.downloadFile(
            DownloadRequest.newBuilder().setPath(nasPath).build()
        );
        OutputStream out = response.getOutputStream();
        while (chunks.hasNext()) {
            chunks.next().getContent().writeTo(out);
        }
        out.flush();
    }

    public String getRewrittenPlaylist(String nasPath, String token) {
        PlaylistRequest.Builder req = PlaylistRequest.newBuilder().setPath(nasPath);
        if (token != null) {
            req.setToken(token);
        }
        return streamingBlockingStub.getRewrittenPlaylist(req.build()).getContent();
    }
}
