package com.spark.common.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestPrincipalGrpcServerInterceptorTest {

    private final RequestPrincipalGrpcServerInterceptor interceptor =
            new RequestPrincipalGrpcServerInterceptor();

    @Test
    void interceptCall_withApplicantMetadata_setsAndClearsRequestPrincipal() {
        Metadata metadata = new Metadata();
        metadata.put(RequestPrincipalGrpcServerInterceptor.APPLICANT_ID_METADATA_KEY, "applicant_001");
        RecordingServerCall call = new RecordingServerCall();
        RecordingHandler handler = new RecordingHandler();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, handler);
        listener.onHalfClose();

        assertThat(handler.principalDuringCall).get().extracting(RequestPrincipal::applicantId).isEqualTo("applicant_001");
        assertThat(handler.principalDuringHalfClose).get().extracting(RequestPrincipal::applicantId).isEqualTo("applicant_001");
        assertThat(RequestPrincipalContext.current()).isEmpty();
        assertThat(call.closedStatus).isNull();
    }

    @Test
    void interceptCall_withoutApplicantMetadata_closesUnauthenticated() {
        RecordingServerCall call = new RecordingServerCall();
        RecordingHandler handler = new RecordingHandler();

        interceptor.interceptCall(call, new Metadata(), handler);

        assertThat(handler.called).isFalse();
        assertThat(call.closedStatus.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(RequestPrincipalContext.current()).isEmpty();
    }

    private static final class RecordingHandler implements ServerCallHandler<String, String> {
        private boolean called;
        private Optional<RequestPrincipal> principalDuringCall = Optional.empty();
        private Optional<RequestPrincipal> principalDuringHalfClose = Optional.empty();

        @Override
        public ServerCall.Listener<String> startCall(ServerCall<String, String> call, Metadata headers) {
            called = true;
            principalDuringCall = RequestPrincipalContext.current();
            return new ServerCall.Listener<>() {
                @Override
                public void onHalfClose() {
                    principalDuringHalfClose = RequestPrincipalContext.current();
                }
            };
        }
    }

    private static final class RecordingServerCall extends ServerCall<String, String> {
        private Status closedStatus;

        @Override
        public void request(int numMessages) {}

        @Override
        public void sendHeaders(Metadata headers) {}

        @Override
        public void sendMessage(String message) {}

        @Override
        public void close(Status status, Metadata trailers) {
            closedStatus = status;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.Service/Protected")
                    .setRequestMarshaller(new StringMarshaller())
                    .setResponseMarshaller(new StringMarshaller())
                    .build();
        }
    }

    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes());
        }

        @Override
        public String parse(InputStream stream) {
            return "";
        }
    }
}
