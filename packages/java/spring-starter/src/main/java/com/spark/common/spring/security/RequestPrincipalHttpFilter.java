package com.spark.common.spring.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestPrincipalHttpFilter extends OncePerRequestFilter {
    public static final String APPLICANT_ID_HEADER = "x-applicant-id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String applicantId = request.getHeader(APPLICANT_ID_HEADER);
        if (applicantId != null && !applicantId.isBlank()) {
            RequestPrincipalContext.set(new RequestPrincipal(applicantId));
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestPrincipalContext.clear();
        }
    }
}
