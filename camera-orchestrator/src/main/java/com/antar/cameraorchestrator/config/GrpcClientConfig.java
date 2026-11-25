package com.antar.cameraorchestrator.config;

import com.antar.cameraorchestrator.grpc.GrpcAuthClientInterceptor;
import com.antar.grpc.filetransfer.FileTransferGrpcServiceGrpc;
import com.antar.grpc.streaming.StreamingGrpcServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    public FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcAsyncStub(
        GrpcChannelFactory channels,
        ServiceTokenRefresher tokenProvider
    ) {
        GrpcAuthClientInterceptor interceptor = new GrpcAuthClientInterceptor(tokenProvider);
        return FileTransferGrpcServiceGrpc.newStub(channels.createChannel("nas-orchestrator"))
            .withInterceptors(interceptor);
    }

    @Bean
    public FileTransferGrpcServiceGrpc.FileTransferGrpcServiceBlockingStub fileTransferGrpcBlockingStub(
        GrpcChannelFactory channels,
        ServiceTokenRefresher tokenProvider
    ) {
        GrpcAuthClientInterceptor interceptor = new GrpcAuthClientInterceptor(tokenProvider);
        return FileTransferGrpcServiceGrpc.newBlockingStub(channels.createChannel("nas-orchestrator"))
            .withInterceptors(interceptor);
    }

    @Bean
    public StreamingGrpcServiceGrpc.StreamingGrpcServiceBlockingStub streamingGrpcBlockingStub(
        GrpcChannelFactory channels,
        ServiceTokenRefresher tokenProvider
    ) {
        GrpcAuthClientInterceptor interceptor = new GrpcAuthClientInterceptor(tokenProvider);
        return StreamingGrpcServiceGrpc.newBlockingStub(channels.createChannel("nas-orchestrator"))
            .withInterceptors(interceptor);
    }
}
