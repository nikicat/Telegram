// Package bridge provides a minimal gomobile-compatible API for running
// a sing-box instance (SOCKS inbound → TUIC outbound) inside an Android app.
package bridge

import (
	"context"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
)

var (
	mu      sync.Mutex
	current *box.Box
)

// Start creates and starts a sing-box instance from the given JSON config.
// If an instance is already running, it is stopped first.
// Safe to call multiple times — restarts with the new config.
func Start(configJSON string) error {
	mu.Lock()
	defer mu.Unlock()

	if current != nil {
		current.Close()
		current = nil
	}

	ctx := include.Context(context.Background())

	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(configJSON)); err != nil {
		return err
	}

	instance, err := box.New(box.Options{
		Options: options,
		Context: ctx,
	})
	if err != nil {
		return err
	}

	if err := instance.Start(); err != nil {
		instance.Close()
		return err
	}

	current = instance
	return nil
}

// Stop closes the running sing-box instance.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()

	if current != nil {
		err := current.Close()
		current = nil
		return err
	}
	return nil
}

// IsRunning returns true if a sing-box instance is currently active.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return current != nil
}
