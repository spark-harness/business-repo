package biz

import (
	"context"
	"testing"
)

func TestHealthUsecase_Check_reportsOkWithInjectedVersion(t *testing.T) {
	// Arrange
	uc := NewHealthUsecase("v1.2.3")

	// Act
	got := uc.Check(context.Background())

	// Assert
	if got.Status != "ok" {
		t.Errorf("status = %q, want %q", got.Status, "ok")
	}
	if got.Version != "v1.2.3" {
		t.Errorf("version = %q, want %q", got.Version, "v1.2.3")
	}
}
