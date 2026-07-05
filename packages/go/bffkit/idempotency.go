package bffkit

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"sync"
	"time"

	khttp "github.com/go-kratos/kratos/v3/transport/http"
)

const (
	HeaderIdempotencyKey        = "Idempotency-Key"
	MaxIdempotencyKeyLength     = 128
	MaxIdempotencyBodyBytes     = 1 << 20
	MaxMemoryIdempotencyRecords = 1024
)

var errRequestBodyTooLarge = errors.New("request body too large")

type IdempotencyRecord struct {
	Fingerprint string
	Status      int
	Header      http.Header
	Body        []byte
}

type IdempotencyStore interface {
	Begin(ctx context.Context, key string, fingerprint string) (IdempotencyReservation, error)
}

type IdempotencyReservation interface {
	Existing() (IdempotencyRecord, bool)
	Commit(ctx context.Context, record IdempotencyRecord) error
	Release()
}

type MemoryIdempotencyStore struct {
	mu       sync.Mutex
	records  map[string]memoryRecord
	order    []string
	inflight map[string]*memoryInflight
	ttl      time.Duration
}

type memoryRecord struct {
	record    IdempotencyRecord
	expiresAt time.Time
}

type memoryInflight struct {
	done chan struct{}
}

func NewMemoryIdempotencyStore(ttl time.Duration) *MemoryIdempotencyStore {
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	return &MemoryIdempotencyStore{
		records:  make(map[string]memoryRecord),
		inflight: make(map[string]*memoryInflight),
		ttl:      ttl,
	}
}

func (s *MemoryIdempotencyStore) Begin(ctx context.Context, key string, fingerprint string) (IdempotencyReservation, error) {
	for {
		s.mu.Lock()
		rec, ok := s.records[key]
		now := time.Now()
		if ok && now.After(rec.expiresAt) {
			delete(s.records, key)
			ok = false
		}
		if ok {
			s.mu.Unlock()
			record := cloneRecord(rec.record)
			if record.Fingerprint != fingerprint {
				return nil, &HTTPError{Status: http.StatusConflict, Code: CodeConflict, Message: "idempotency key reused with different request"}
			}
			return &memoryReservation{existing: record}, nil
		}
		if inflight, ok := s.inflight[key]; ok {
			done := inflight.done
			s.mu.Unlock()
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-done:
				continue
			}
		}
		inflight := &memoryInflight{done: make(chan struct{})}
		s.inflight[key] = inflight
		s.mu.Unlock()
		return &memoryReservation{store: s, key: key, fingerprint: fingerprint, inflight: inflight}, nil
	}
}

func IdempotencyFilter(store IdempotencyStore) khttp.FilterFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !isIdempotencyMethod(r.Method) {
				next.ServeHTTP(w, r)
				return
			}

			key := r.Header.Get(HeaderIdempotencyKey)
			if key == "" {
				ErrorEncoder(w, r, ValidationError([]FieldError{{Field: HeaderIdempotencyKey, Message: "Idempotency-Key is required"}}))
				return
			}
			if !validIdempotencyKey(key) {
				ErrorEncoder(w, r, ValidationError([]FieldError{{Field: HeaderIdempotencyKey, Message: "Idempotency-Key must be 1-128 characters using letters, numbers, dot, underscore, colon, or dash"}}))
				return
			}
			fingerprint, err := requestFingerprint(r)
			if err != nil {
				if errors.Is(err, errRequestBodyTooLarge) {
					ErrorEncoder(w, r, &HTTPError{Status: http.StatusRequestEntityTooLarge, Code: CodeValidation, Message: "request body too large"})
					return
				}
				ErrorEncoder(w, r, &HTTPError{Status: http.StatusBadRequest, Code: CodeValidation, Message: "invalid request body"})
				return
			}
			reservation, err := store.Begin(r.Context(), key, fingerprint)
			if err != nil {
				ErrorEncoder(w, r, normalizeError(err))
				return
			}
			if rec, ok := reservation.Existing(); ok {
				writeRecord(w, rec)
				return
			}
			defer reservation.Release()

			capture := newCaptureResponseWriter(w)
			next.ServeHTTP(capture, r)
			record := capture.Record()
			record.Fingerprint = fingerprint
			if err := reservation.Commit(r.Context(), record); err != nil {
				ErrorEncoder(w, r, normalizeError(err))
				return
			}
			writeRecord(w, record)
		})
	}
}

func isIdempotencyMethod(method string) bool {
	return method == http.MethodPost || method == http.MethodPut || method == http.MethodPatch || method == http.MethodDelete
}

func writeRecord(w http.ResponseWriter, rec IdempotencyRecord) {
	for key, values := range rec.Header {
		w.Header().Del(key)
		for _, value := range values {
			w.Header().Add(key, value)
		}
	}
	w.WriteHeader(rec.Status)
	_, _ = w.Write(rec.Body)
}

type captureResponseWriter struct {
	http.ResponseWriter
	status int
	header http.Header
	body   bytes.Buffer
}

func newCaptureResponseWriter(w http.ResponseWriter) *captureResponseWriter {
	return &captureResponseWriter{ResponseWriter: w, status: http.StatusOK, header: make(http.Header)}
}

func (w *captureResponseWriter) Header() http.Header {
	return w.header
}

func (w *captureResponseWriter) WriteHeader(status int) {
	w.status = status
}

func (w *captureResponseWriter) Write(data []byte) (int, error) {
	return w.body.Write(data)
}

func (w *captureResponseWriter) SetErrorCode(code string) {
	SetErrorCode(w.ResponseWriter, code)
}

func (w *captureResponseWriter) Record() IdempotencyRecord {
	return IdempotencyRecord{
		Status: w.status,
		Header: w.header.Clone(),
		Body:   append([]byte(nil), w.body.Bytes()...),
	}
}

func cloneRecord(record IdempotencyRecord) IdempotencyRecord {
	return IdempotencyRecord{
		Fingerprint: record.Fingerprint,
		Status:      record.Status,
		Header:      record.Header.Clone(),
		Body:        append([]byte(nil), record.Body...),
	}
}

func (s *MemoryIdempotencyStore) RecordCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.evictExpiredLocked(time.Now())
	return len(s.records)
}

type memoryReservation struct {
	store       *MemoryIdempotencyStore
	key         string
	fingerprint string
	inflight    *memoryInflight
	existing    IdempotencyRecord
}

func (r *memoryReservation) Existing() (IdempotencyRecord, bool) {
	if r.store != nil {
		return IdempotencyRecord{}, false
	}
	return cloneRecord(r.existing), true
}

func (r *memoryReservation) Commit(_ context.Context, record IdempotencyRecord) error {
	if r.store == nil {
		return nil
	}
	r.store.mu.Lock()
	defer r.store.mu.Unlock()

	now := time.Now()
	r.store.evictExpiredLocked(now)
	if _, exists := r.store.records[r.key]; !exists {
		r.store.order = append(r.store.order, r.key)
	}
	r.store.records[r.key] = memoryRecord{record: cloneRecord(record), expiresAt: now.Add(r.store.ttl)}
	r.store.evictOldestLocked()
	delete(r.store.inflight, r.key)
	close(r.inflight.done)
	r.inflight = nil
	return nil
}

func (r *memoryReservation) Release() {
	if r.store == nil || r.inflight == nil {
		return
	}
	r.store.mu.Lock()
	defer r.store.mu.Unlock()

	if current := r.store.inflight[r.key]; current == r.inflight {
		delete(r.store.inflight, r.key)
		close(r.inflight.done)
	}
	r.inflight = nil
}

func requestFingerprint(r *http.Request) (string, error) {
	var body []byte
	if r.Body != nil {
		var err error
		body, err = io.ReadAll(io.LimitReader(r.Body, MaxIdempotencyBodyBytes+1))
		if err != nil {
			return "", err
		}
		if len(body) > MaxIdempotencyBodyBytes {
			r.Body = io.NopCloser(bytes.NewReader(body[:MaxIdempotencyBodyBytes]))
			return "", errRequestBodyTooLarge
		}
		r.Body = io.NopCloser(bytes.NewReader(body))
	}
	sum := sha256.Sum256([]byte(r.Method + "\n" + r.URL.Path + "\n" + string(body)))
	return hex.EncodeToString(sum[:]), nil
}

func validIdempotencyKey(key string) bool {
	if key == "" || len(key) > MaxIdempotencyKeyLength {
		return false
	}
	for _, ch := range key {
		if ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' {
			continue
		}
		switch ch {
		case '.', '_', ':', '-':
			continue
		default:
			return false
		}
	}
	return true
}

func (s *MemoryIdempotencyStore) evictExpiredLocked(now time.Time) {
	if len(s.records) == 0 {
		return
	}
	next := s.order[:0]
	for _, key := range s.order {
		rec, ok := s.records[key]
		if !ok {
			continue
		}
		if now.After(rec.expiresAt) {
			delete(s.records, key)
			continue
		}
		next = append(next, key)
	}
	s.order = next
}

func (s *MemoryIdempotencyStore) evictOldestLocked() {
	for len(s.records) > MaxMemoryIdempotencyRecords && len(s.order) > 0 {
		key := s.order[0]
		s.order = s.order[1:]
		delete(s.records, key)
	}
}
