package com.spark.common.spring.security;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

public class RequestPrincipalGrpcServerInterceptor implements ServerInterceptor {
    public static final Metadata.Key<String> APPLICANT_ID_METADATA_KEY =
            Metadata.Key.of("x-applicant-id", Metadata.ASCII_STRING_MARSHALLER);
    private final String unauthenticatedDescription;

    public RequestPrincipalGrpcServerInterceptor() {
        this("request principal is required");
    }

    public RequestPrincipalGrpcServerInterceptor(String unauthenticatedDescription) {
        this.unauthenticatedDescription = unauthenticatedDescription;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String applicantId = headers.get(APPLICANT_ID_METADATA_KEY);
        if (applicantId == null || applicantId.isBlank()) {
            call.close(Status.UNAUTHENTICATED.withDescription(unauthenticatedDescription), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        RequestPrincipalContext.set(new RequestPrincipal(applicantId));
        try {
            ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
            return new PrincipalClearingListener<>(listener, new RequestPrincipal(applicantId));
        } finally {
            RequestPrincipalContext.clear();
        }
    }

    private static final class PrincipalClearingListener<ReqT>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
        private final RequestPrincipal principal;

        private PrincipalClearingListener(ServerCall.Listener<ReqT> delegate, RequestPrincipal principal) {
            super(delegate);
            this.principal = principal;
        }

        @Override
        public void onMessage(ReqT message) {
            RequestPrincipalContext.set(principal);
            try {
                super.onMessage(message);
            } finally {
                RequestPrincipalContext.clear();
            }
        }

        @Override
        public void onHalfClose() {
            RequestPrincipalContext.set(principal);
            try {
                super.onHalfClose();
            } finally {
                RequestPrincipalContext.clear();
            }
        }

        @Override
        public void onCancel() {
            RequestPrincipalContext.set(principal);
            try {
                super.onCancel();
            } finally {
                RequestPrincipalContext.clear();
            }
        }

        @Override
        public void onComplete() {
            RequestPrincipalContext.set(principal);
            try {
                super.onComplete();
            } finally {
                RequestPrincipalContext.clear();
            }
        }

        @Override
        public void onReady() {
            RequestPrincipalContext.set(principal);
            try {
                super.onReady();
            } finally {
                RequestPrincipalContext.clear();
            }
        }
    }
}
