package state

import (
    "sync"
)

type State struct {
    seenDates    map[string]bool
    processing   map[string]bool
    mu           sync.RWMutex
}

func NewState() *State {
    return &State{
        seenDates:  make(map[string]bool),
        processing: make(map[string]bool),
    }
}

func (s *State) IsSeen(date string) bool {
    s.mu.RLock()
    defer s.mu.RUnlock()
    return s.seenDates[date]
}

func (s *State) MarkSeen(date string) {
    s.mu.Lock()
    defer s.mu.Unlock()
    s.seenDates[date] = true
}

func (s *State) IsProcessing(date string) bool {
    s.mu.RLock()
    defer s.mu.RUnlock()
    return s.processing[date]
}

func (s *State) StartProcessing(date string) bool {
    s.mu.Lock()
    defer s.mu.Unlock()
    if s.processing[date] {
        return false
    }
    s.processing[date] = true
    return true
}

func (s *State) StopProcessing(date string) {
    s.mu.Lock()
    defer s.mu.Unlock()
    delete(s.processing, date)
}
