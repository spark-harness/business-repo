package bffkit

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestIdempotencyFilter_replaysFirstWriteResponseForSameKey(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	calls := 0
	filter := IdempotencyFilter(store)
	handler := filter(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = fmt.Fprintf(w, `{"call":%d}`, calls)
	}))

	first := httptest.NewRecorder()
	firstReq := httptest.NewRequest(http.MethodPost, "/api/v1/applications", nil)
	firstReq.Header.Set(HeaderIdempotencyKey, "idem-1")
	handler.ServeHTTP(first, firstReq)

	second := httptest.NewRecorder()
	secondReq := httptest.NewRequest(http.MethodPost, "/api/v1/applications", nil)
	secondReq.Header.Set(HeaderIdempotencyKey, "idem-1")
	handler.ServeHTTP(second, secondReq)

	if calls != 1 {
		t.Fatalf("handler calls = %d, want 1", calls)
	}
	if second.Code != http.StatusCreated {
		t.Fatalf("second status = %d, want %d", second.Code, http.StatusCreated)
	}
	if second.Body.String() != first.Body.String() {
		t.Fatalf("second body = %s, want replay %s", second.Body.String(), first.Body.String())
	}
}

func TestIdempotencyFilter_doesNotReplayOuterFilterHeaders(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	handler := CORSFilter(CORSConfig{AllowedOrigins: []string{"http://localhost:3001"}})(
		IdempotencyFilter(store)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, _ = io.WriteString(w, `{"ok":true}`)
		})),
	)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/applications", nil)
	req.Header.Set("Origin", "http://localhost:3001")
	req.Header.Set(HeaderIdempotencyKey, "idem-cors")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	values := rec.Header().Values("Access-Control-Allow-Origin")
	if len(values) != 1 || values[0] != "http://localhost:3001" {
		t.Fatalf("Access-Control-Allow-Origin values = %#v", values)
	}
}

func TestIdempotencyFilter_doesNotCacheReadRequests(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	calls := 0
	handler := IdempotencyFilter(store)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusOK)
	}))

	for range 2 {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
		req.Header.Set(HeaderIdempotencyKey, "idem-read")
		handler.ServeHTTP(httptest.NewRecorder(), req)
	}

	if calls != 2 {
		t.Fatalf("handler calls = %d, want 2", calls)
	}
}

func TestIdempotencyFilter_rejectsWriteRequestWithoutKey(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	calls := 0
	handler := IdempotencyFilter(store)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusCreated)
	}))

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/applications", nil)
	handler.ServeHTTP(rec, req)

	if calls != 0 {
		t.Fatalf("handler calls = %d, want 0", calls)
	}
	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d (body: %s)", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
}

func TestIdempotencyFilter_rejectsSameKeyWithDifferentRequestFingerprint(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	handler := IdempotencyFilter(store)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"ok":true}`)
	}))

	first := httptest.NewRecorder()
	firstReq := httptest.NewRequest(http.MethodPost, "/api/v1/applications", strings.NewReader(`{"amount":100}`))
	firstReq.Header.Set(HeaderIdempotencyKey, "idem-fingerprint")
	handler.ServeHTTP(first, firstReq)

	second := httptest.NewRecorder()
	secondReq := httptest.NewRequest(http.MethodPost, "/api/v1/applications", strings.NewReader(`{"amount":200}`))
	secondReq.Header.Set(HeaderIdempotencyKey, "idem-fingerprint")
	handler.ServeHTTP(second, secondReq)

	if second.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d (body: %s)", second.Code, http.StatusConflict, second.Body.String())
	}
}

func TestIdempotencyFilter_concurrentSameKeyExecutesHandlerOnce(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	var calls atomic.Int32
	release := make(chan struct{})
	handler := IdempotencyFilter(store)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		<-release
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"call":1}`)
	}))

	const requestCount = 2
	var wg sync.WaitGroup
	responses := make([]*httptest.ResponseRecorder, requestCount)
	for i := range requestCount {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			req := httptest.NewRequest(http.MethodPost, "/api/v1/applications", strings.NewReader(`{"amount":100}`))
			req.Header.Set(HeaderIdempotencyKey, "idem-concurrent")
			rec := httptest.NewRecorder()
			responses[index] = rec
			handler.ServeHTTP(rec, req)
		}(i)
	}

	for calls.Load() == 0 {
		time.Sleep(time.Millisecond)
	}
	close(release)
	wg.Wait()

	if calls.Load() != 1 {
		t.Fatalf("handler calls = %d, want 1", calls.Load())
	}
	for i, rec := range responses {
		if rec.Code != http.StatusCreated {
			t.Fatalf("response %d status = %d, want %d", i, rec.Code, http.StatusCreated)
		}
		if rec.Body.String() != `{"call":1}` {
			t.Fatalf("response %d body = %s, want replayed body", i, rec.Body.String())
		}
	}
}

func TestIdempotencyFilter_rejectsInvalidOrLongKey(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	handler := IdempotencyFilter(store)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
	}))

	tests := []string{
		strings.Repeat("a", MaxIdempotencyKeyLength+1),
		"invalid key with spaces",
	}

	for _, key := range tests {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/applications", nil)
		req.Header.Set(HeaderIdempotencyKey, key)
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnprocessableEntity {
			t.Fatalf("key %q status = %d, want %d", key, rec.Code, http.StatusUnprocessableEntity)
		}
	}
}

func TestIdempotencyFilter_rejectsRequestBodyAboveLimit(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	handler := IdempotencyFilter(store)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
	}))

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/applications", strings.NewReader(strings.Repeat("x", MaxIdempotencyBodyBytes+1)))
	req.Header.Set(HeaderIdempotencyKey, "idem-large-body")
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusRequestEntityTooLarge)
	}
}

func TestMemoryIdempotencyStore_capsStoredRecords(t *testing.T) {
	store := NewMemoryIdempotencyStore(time.Hour)
	handler := IdempotencyFilter(store)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"ok":true}`)
	}))

	for i := 0; i < MaxMemoryIdempotencyRecords+1; i++ {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/applications", strings.NewReader(fmt.Sprintf(`{"request":%d}`, i)))
		req.Header.Set(HeaderIdempotencyKey, fmt.Sprintf("idem-%d", i))
		handler.ServeHTTP(httptest.NewRecorder(), req)
	}

	if got := store.RecordCount(); got > MaxMemoryIdempotencyRecords {
		t.Fatalf("record count = %d, want <= %d", got, MaxMemoryIdempotencyRecords)
	}
}
