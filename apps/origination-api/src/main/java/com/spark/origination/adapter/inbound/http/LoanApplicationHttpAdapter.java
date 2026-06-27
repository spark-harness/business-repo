package com.spark.origination.adapter.inbound.http;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.origination.application.CreateLoanApplicationCommand;
import com.spark.origination.application.CreateLoanApplicationUseCase;
import com.spark.origination.application.GetLoanApplicationUseCase;
import com.spark.origination.application.PatchLoanApplicationCommand;
import com.spark.origination.application.PatchLoanApplicationUseCase;
import com.spark.origination.domain.AcceptedQuote;
import com.spark.origination.domain.LoanApplication;
import com.spark.origination.domain.LoanTerms;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@InboundAdapter
@RestController
public class LoanApplicationHttpAdapter {
    private final CreateLoanApplicationUseCase createUseCase;
    private final GetLoanApplicationUseCase getUseCase;
    private final PatchLoanApplicationUseCase patchUseCase;

    public LoanApplicationHttpAdapter(
            CreateLoanApplicationUseCase createUseCase,
            GetLoanApplicationUseCase getUseCase,
            PatchLoanApplicationUseCase patchUseCase) {
        this.createUseCase = createUseCase;
        this.getUseCase = getUseCase;
        this.patchUseCase = patchUseCase;
    }

    @PostMapping("/api/v1/loan-applications")
    public LoanApplicationSummaryResponse create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateLoanApplicationRequest request) {
        return LoanApplicationSummaryResponse.from(createUseCase.create(new CreateLoanApplicationCommand(
                request.productCode(), request.loan().toDomain(), request.quoteId(), idempotencyKey)));
    }

    @GetMapping("/api/v1/loan-applications/{applicationId}")
    public LoanApplicationDetailResponse get(@PathVariable("applicationId") String applicationId) {
        return LoanApplicationDetailResponse.from(getUseCase.get(applicationId));
    }

    @PatchMapping("/api/v1/loan-applications/{applicationId}")
    public LoanApplicationSummaryResponse patch(
            @PathVariable("applicationId") String applicationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PatchLoanApplicationRequest request) {
        return LoanApplicationSummaryResponse.from(patchUseCase.patch(new PatchLoanApplicationCommand(
                applicationId, request.loan().toDomain(), request.quoteId(), idempotencyKey)));
    }

    public record CreateLoanApplicationRequest(String productCode, LoanRequest loan, String quoteId) {}

    public record PatchLoanApplicationRequest(LoanRequest loan, String quoteId) {}

    public record LoanRequest(BigDecimal amount, int term, String purpose) {
        LoanTerms toDomain() {
            return new LoanTerms(amount, term, purpose);
        }
    }

    public record LoanApplicationSummaryResponse(String applicationId, String status, String currentStep) {
        static LoanApplicationSummaryResponse from(LoanApplication application) {
            return new LoanApplicationSummaryResponse(
                    application.applicationId(),
                    application.status().value(),
                    application.currentStep().value());
        }
    }

    public record LoanApplicationDetailResponse(
            String applicationId,
            LoanResponse loan,
            AcceptedQuoteResponse acceptedQuote,
            String status,
            String currentStep) {
        static LoanApplicationDetailResponse from(LoanApplication application) {
            return new LoanApplicationDetailResponse(
                    application.applicationId(),
                    LoanResponse.from(application.loan()),
                    AcceptedQuoteResponse.from(application.acceptedQuote()),
                    application.status().value(),
                    application.currentStep().value());
        }
    }

    public record LoanResponse(String amount, int term, String purpose) {
        static LoanResponse from(LoanTerms loan) {
            return new LoanResponse(loan.amount().toPlainString(), loan.term(), loan.purpose());
        }
    }

    public record AcceptedQuoteResponse(
            String quoteId,
            String monthly,
            String apr,
            String totalInterest,
            String totalPayable,
            String validUntil) {
        static AcceptedQuoteResponse from(AcceptedQuote quote) {
            return new AcceptedQuoteResponse(
                    quote.quoteId(),
                    quote.monthly().toPlainString(),
                    quote.apr().toPlainString(),
                    quote.totalInterest().toPlainString(),
                    quote.totalPayable().toPlainString(),
                    quote.validUntil().toString());
        }
    }
}
