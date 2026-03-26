package notifier

import (
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
    sort.Slice(weekends, func(i, j int) bool { return weekends[i].Date < weekends[j].Date })
    sort.Slice(weekdays, func(i, j int) bool { return weekdays[i].Date < weekdays[j].Date })

    // Combine weekends first
    all := append(weekends, weekdays...)

    // Prepare up to 3 actions (prioritize weekends)
    actions = make([]Action, 0, 3)
    for i := 0; i < len(all) && i < 3; i++ {
        a := all[i]
        label := fmt.Sprintf("%s: %s", weekendOrWeekday(a.Date), a.Date)
        actions = append(actions, Action{
            Action: "view",
            Label:  label,
            URL:    a.BookingURL,
        })
    }

    // Build the message body: a simple list of dates with emojis, formatted like "Mar 26"
    var msg strings.Builder
    msg.WriteString("Available: ")
    for i, a := range all {
        if i > 0 {
            msg.WriteString(", ")
        }
        // Parse date to format "Jan 2"
        t, _ := time.Parse("2006-01-02", a.Date)
        dateFmt := t.Format("Jan 2")
        emoji := "📅"
        if isWeekend(a.Date) {
            emoji = "🌟"
        }
        msg.WriteString(fmt.Sprintf("%s %s", emoji, dateFmt))
    }
    if len(all) > 3 {
        msg.WriteString(fmt.Sprintf(" (+ %d more)", len(all)-3))
    }

    title = "Appointment Available"
    message = msg.String()
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

// SendNotification sends a structured notification using ntfy's header‑driven API.
func (n *Ntfy) SendNotification(title, msg string, priority Priority, actions []Action) error {
    req, err := http.NewRequest("POST", n.URL, strings.NewReader(msg))
    if err != nil {
        return err
    }

    req.Header.Set("Title", title)
    req.Header.Set("Priority", fmt.Sprintf("%d", priority))
    req.Header.Set("Tags", "calendar,clock")

    if len(actions) > 0 {
        actionsJSON, err := json.Marshal(actions)
        if err != nil {
            return fmt.Errorf("failed to marshal actions: %w", err)
        }
        req.Header.Set("Actions", string(actionsJSON))
    }

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
