package com.spark.common.spring.security;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.core.Ordered;

public class RequestPrincipalGrpcClientInterceptor implements ClientInterceptor, Ordered {
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.removeAll(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY);
                RequestPrincipalContext.current()
                        .map(RequestPrincipal::applicantId)
                        .filter(applicantId -> !applicantId.isBlank())
                        .ifPresent(applicantId ->
                                headers.put(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY, applicantId));
                super.start(responseListener, headers);
            }
        };
    }
}
