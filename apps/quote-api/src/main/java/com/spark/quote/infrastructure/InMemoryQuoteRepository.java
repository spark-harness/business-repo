package com.spark.quote.infrastructure;

import com.spark.quote.application.QuoteRepository;
import com.spark.quote.domain.Quote;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryQuoteRepository implements QuoteRepository {
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();

    @Override
    public void save(Quote quote) {
        quotes.put(quote.quoteId(), quote);
    }

    @Override
    public Optional<Quote> findById(String quoteId) {
        return Optional.ofNullable(quotes.get(quoteId));
    }

    public int count() {
        return quotes.size();
    }
}
