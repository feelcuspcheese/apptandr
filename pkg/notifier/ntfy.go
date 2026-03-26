package notifier

import (
    "bytes"
    "encoding/json"
    "fmt"
    "net/http"
    "sort"
    "strings"
    "time"
)

type Ntfy struct {
    Topic  string
    URL    string
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
    PriorityMin     Priority = 1
    PriorityLow     Priority = 2
    PriorityDefault Priority = 3
    PriorityHigh    Priority = 4
    PriorityUrgent  Priority = 5
)

type Message struct {
    Topic    string   `json:"topic"`
    Title    string   `json:"title"`
    Message  string   `json:"message"`
    Priority Priority `json:"priority"`
    Actions  []Action `json:"actions,omitempty"`
    Tags     []string `json:"tags,omitempty"`
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

// BuildNotification creates a title, message, and up to 3 actions (prioritizing weekends)
func BuildNotification(availabilities []AvailabilityWithLink) (title, message string, actions []Action) {
    if len(availabilities) == 0 {
        return "", "", nil
    }

    // Separate weekends and weekdays
    var weekends, weekdays []AvailabilityWithLink
    for _, a := range availabilities {
        if isWeekend(a.Date) {
            weekends = append(weekends, a)
        } else {
            weekdays = append(weekdays, a)
        }
    }
    // Sort each by date
    sort.Slice(weekends, func(i, j int) bool { return weekends[i].Date < weekends[j].Date })
    sort.Slice(weekdays, func(i, j int) bool { return weekdays[i].Date < weekdays[j].Date })

    // Combined list: weekends first
    all := append(weekends, weekdays...)

    // Prepare up to 3 actions (weekends prioritized)
    actions = make([]Action, 0, 3)
    for i := 0; i < len(all) && i < 3; i++ {
        a := all[i]
        label := fmt.Sprintf("%s: %s", weekendOrWeekday(a.Date), a.Date)
        actions = append(actions, Action{
            Action: "view",
            Label:  label,
            URL:    a.BookingURL, // must be absolute
        })
    }

    // Build a concise message with a summary and fallback links
    var msg strings.Builder
    msg.WriteString("Available: ")
    for i, a := range all {
        if i > 0 {
            msg.WriteString(", ")
        }
        msg.WriteString(a.Date)
        if i < 3 {
            msg.WriteString(" [button]")
        }
    }
    if len(all) > 3 {
        msg.WriteString(fmt.Sprintf(" (+ %d more)", len(all)-3))
    }
    // Add a newline and then the full list with links as plain text (fallback)
    msg.WriteString("\n\nFull list:\n")
    for i, a := range all {
        msg.WriteString(fmt.Sprintf("%s: %s\n", a.Date, a.BookingURL))
    }

    message = msg.String()
    title = "Appointment Available"
    return
}

func weekendOrWeekday(dateStr string) string {
    if isWeekend(dateStr) {
        return "Weekend"
    }
    return "Weekday"
}

func isWeekend(dateStr string) bool {
    t, err := time.Parse("2006-01-02", dateStr)
    if err != nil {
        return false
    }
    wd := t.Weekday()
    return wd == time.Saturday || wd == time.Sunday
}

// SendNotification sends a notification with the given title, message, and actions.
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
        return err
    }
    defer resp.Body.Close()
    if resp.StatusCode != http.StatusOK {
        return fmt.Errorf("ntfy returned %d", resp.StatusCode)
    }
    return nil
}
