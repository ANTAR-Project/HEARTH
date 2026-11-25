package com.antar.cameraorchestrator.grpc;

import com.antar.cameraorchestrator.config.ServiceTokenRefresher;
import io.grpc.*;

public class GrpcAuthClientInterceptor implements ClientInterceptor {
    private static final Metadata.Key<String> AUTH_TOKEN_METADATA_KEY =
        Metadata.Key.of("x-auth-token", Metadata.ASCII_STRING_MARSHALLER);

    private final ServiceTokenRefresher tokenProvider;

    public GrpcAuthClientInterceptor(ServiceTokenRefresher tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next
    ) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(AUTH_TOKEN_METADATA_KEY, tokenProvider.get());
                super.start(responseListener, headers);
            }
        };
    }
}
