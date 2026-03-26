package notifier

import (
    "bytes"
    "encoding/json"
    "fmt"
    "io"
    "net/http"
    "sort"
    "time"
)

type Ntfy struct {
    Topic string
    URL   string
    client *http.Client
}

func NewNtfy(topic string) *Ntfy {
    return &Ntfy{
        Topic:  topic,
        URL:    fmt.Sprintf("https://ntfy.sh/%s", topic),
        client: &http.Client{Timeout: 10 * time.Second},
    }
}

type Priority int

const (
    PriorityMin Priority = 1
    PriorityLow Priority = 2
    PriorityDefault Priority = 3
    PriorityHigh Priority = 4
    PriorityUrgent Priority = 5
)

type Message struct {
    Topic    string            `json:"topic"`
    Title    string            `json:"title"`
    Message  string            `json:"message"`
    Priority Priority          `json:"priority"`
    Actions  []Action          `json:"actions,omitempty"`
    Tags     []string          `json:"tags,omitempty"`
}

type Action struct {
    Action string `json:"action"`
    Label  string `json:"label"`
    URL    string `json:"url"`
}

type AvailabilityWithLink struct {
    Date       string
    BookingURL string
}

func (n *Ntfy) SendNotification(title, msg string, priority Priority, actions []Action) error {
    m := Message{
        Topic:    n.Topic,
        Title:    title,
        Message:  msg,
        Priority: priority,
        Actions:  actions,
        Tags:     []string{"calendar", "clock"},
    }
    data, err := json.Marshal(m)
    if err != nil {
        return err
    }
    req, err := http.NewRequest("POST", n.URL, bytes.NewReader(data))
    if err != nil {
        return err
    }
    req.Header.Set("Content-Type", "application/json")
    resp, err := n.client.Do(req)
    if err != nil {
        return fmt.Errorf("request failed: %w", err)
    }
    defer resp.Body.Close()
    if resp.StatusCode != http.StatusOK {
        body, _ := io.ReadAll(resp.Body)
        return fmt.Errorf("ntfy returned %d: %s", resp.StatusCode, string(body))
    }
    return nil
}

func PrioritizeActions(availabilities []AvailabilityWithLink) []Action {
    var weekends, weekdays []AvailabilityWithLink
    for _, a := range availabilities {
        if isWeekend(a.Date) {
            weekends = append(weekends, a)
        } else {
            weekdays = append(weekdays, a)
        }
    }
    sort.Slice(weekends, func(i, j int) bool { return weekends[i].Date < weekends[j].Date })
    sort.Slice(weekdays, func(i, j int) bool { return weekdays[i].Date < weekdays[j].Date })
    actions := make([]Action, 0, len(availabilities))
    for _, a := range weekends {
        actions = append(actions, Action{
            Action: "view",
            Label:  fmt.Sprintf("Weekend: %s", a.Date),
            URL:    a.BookingURL,
        })
    }
    for _, a := range weekdays {
        actions = append(actions, Action{
            Action: "view",
            Label:  fmt.Sprintf("Weekday: %s", a.Date),
            URL:    a.BookingURL,
        })
    }
    return actions
}

func isWeekend(dateStr string) bool {
    t, err := time.Parse("2006-01-02", dateStr)
    if err != nil {
        return false
    }
    wd := t.Weekday()
    return wd == time.Saturday || wd == time.Sunday
}
