package service

import (
	"context"
	nethttp "net/http"

	khttp "github.com/go-kratos/kratos/v3/transport/http"
)

func requestHeaders(ctx context.Context) nethttp.Header {
	req, ok := khttp.RequestFromServerContext(ctx)
	if !ok {
		return nethttp.Header{}
	}
	return req.Header
}
