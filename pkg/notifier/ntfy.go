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

    all := append(weekends, weekdays...)

    // Actions: up to 3 (weekends first)
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

    // Build the plain text message with emojis
    var msg strings.Builder
    msg.WriteString("Available: ")
    for i, a := range all {
        if i > 0 {
            msg.WriteString(", ")
        }
        // Use emoji: 🌟 for weekend, 📅 for weekday
        emoji := "📅"
        if isWeekend(a.Date) {
            emoji = "🌟"
        }
        msg.WriteString(fmt.Sprintf("%s %s", emoji, a.Date))
        if i < 3 {
            msg.WriteString(" [button]")
        }
    }
    if len(all) > 3 {
        msg.WriteString(fmt.Sprintf(" (+ %d more)", len(all)-3))
    }
    msg.WriteString("\n\nFull list:\n")
    for _, a := range all {
        emoji := "📅"
        if isWeekend(a.Date) {
            emoji = "🌟"
        }
        msg.WriteString(fmt.Sprintf("%s %s: %s\n", emoji, a.Date, a.BookingURL))
    }

    title = "Appointment Available"
    message = msg.String()
    return
}
