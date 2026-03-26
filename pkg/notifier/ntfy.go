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

// AvailabilityWithLink holds availability data for notifications
type AvailabilityWithLink struct {
    Date       string
    BookingURL string
}

// BuildNotification creates a title, message, and up to 3 actions (prioritizing weekends)
// It returns the title, message string, and the actions slice (max 3).
// baseURL is the site's base URL (e.g., "https://spl.libcal.com") to prepend to relative booking URLs.
func BuildNotification(availabilities []AvailabilityWithLink, baseURL string) (title, message string, actions []Action) {
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
    // Sort each by date (ascending)
    sort.Slice(weekends, func(i, j int) bool { return weekends[i].Date < weekends[j].Date })
    sort.Slice(weekdays, func(i, j int) bool { return weekdays[i].Date < weekdays[j].Date })

    // Combined list with weekends first
    all := append(weekends, weekdays...)

    // Prepare actions (max 3) – take first 3 from the prioritized list
    actions = make([]Action, 0, 3)
    for i := 0; i < len(all) && i < 3; i++ {
        a := all[i]
        fullURL := a.BookingURL
        // If URL is relative, prepend baseURL
        if strings.HasPrefix(fullURL, "/") {
            fullURL = strings.TrimRight(baseURL, "/") + fullURL
        }
        label := fmt.Sprintf("%s: %s", weekendOrWeekday(a.Date), a.Date)
        actions = append(actions, Action{
            Action: "view",
            Label:  label,
            URL:    fullURL,
        })
    }

    // Prepare message summary for all dates (including those not in actions)
    dateList := make([]string, len(all))
    for i, a := range all {
        dateList[i] = fmt.Sprintf("%s (%s)", a.Date, weekendOrWeekday(a.Date))
    }
    message = strings.Join(dateList, ", ")

    // Add note if there are more than 3 dates
    if len(all) > 3 {
        message += fmt.Sprintf("\n(Only the first 3 are shown as buttons; %d more dates available)", len(all)-3)
    }

    title = "Appointment Available!"
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
