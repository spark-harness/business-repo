package com.spark.common.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestPrincipalHttpFilterTest {
    @Test
    void doFilter_withApplicantHeader_setsPrincipalForRequest() throws ServletException, IOException {
        RequestPrincipalHttpFilter filter = new RequestPrincipalHttpFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestPrincipalHttpFilter.APPLICANT_ID_HEADER, "applicant_001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> applicantId = new AtomicReference<>();
        FilterChain chain = (ignoredRequest, ignoredResponse) ->
                applicantId.set(RequestPrincipalContext.required().applicantId());

        filter.doFilter(request, response, chain);

        assertThat(applicantId).hasValue("applicant_001");
        assertThat(RequestPrincipalContext.current()).isEmpty();
    }
}
