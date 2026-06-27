package com.spark.quote.application;

import com.spark.quote.domain.Quote;
import java.util.Optional;

public interface QuoteRepository {
    void save(Quote quote);

    Optional<Quote> findById(String quoteId);
}
