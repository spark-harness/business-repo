package com.spark.origination.application;

import com.spark.origination.domain.AcceptedQuote;

public interface QuoteGateway {
    AcceptedQuote get(String quoteId);
}
