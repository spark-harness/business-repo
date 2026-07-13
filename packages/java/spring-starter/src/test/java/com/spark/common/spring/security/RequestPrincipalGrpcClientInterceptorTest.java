package com.spark.common.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RequestPrincipalGrpcClientInterceptorTest {
    private final RequestPrincipalGrpcClientInterceptor interceptor = new RequestPrincipalGrpcClientInterceptor();
    private final CapturingChannel channel = new CapturingChannel();

    @AfterEach
    void tearDown() {
        RequestPrincipalContext.clear();
    }

    @Test
    void start_withPrincipal_attachesApplicantMetadata() {
        RequestPrincipalContext.set(new RequestPrincipal("applicant_001"));

        ClientCall<String, String> call = interceptor.interceptCall(testMethod(), CallOptions.DEFAULT, channel);
        call.start(new ClientCall.Listener<>() {}, new Metadata());

        assertThat(channel.call.headers.get(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY))
                .isEqualTo("applicant_001");
    }

    @Test
    void start_withoutPrincipal_doesNotAttachApplicantMetadata() {
		Metadata headers = new Metadata();
		headers.put(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY, "stale-applicant");

        ClientCall<String, String> call = interceptor.interceptCall(testMethod(), CallOptions.DEFAULT, channel);
        call.start(new ClientCall.Listener<>() {}, headers);

        assertThat(channel.call.headers.get(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY)).isNull();
    }

    private static MethodDescriptor<String, String> testMethod() {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName("spark.test.TestService", "Call"))
                .setRequestMarshaller(new StringMarshaller())
                .setResponseMarshaller(new StringMarshaller())
                .build();
    }

    private static final class CapturingChannel extends Channel {
        private CapturingClientCall call;

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
            call = new CapturingClientCall();
            @SuppressWarnings("unchecked")
            ClientCall<ReqT, RespT> typed = (ClientCall<ReqT, RespT>) call;
            return typed;
        }

        @Override
        public String authority() {
            return "test";
        }
    }

    private static final class CapturingClientCall extends ClientCall<String, String> {
        private Metadata headers;

        @Override
        public void start(Listener<String> responseListener, Metadata headers) {
            this.headers = headers;
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(String message, Throwable cause) {}

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(String message) {}
    }

    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
