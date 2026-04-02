package config

import (
    "time"
)

type Museum struct {
    Name     string `mapstructure:"name" json:"name"`
    Slug     string `mapstructure:"slug" json:"slug"`
    MuseumID string `mapstructure:"museumid" json:"museumid"`
}

type Site struct {
    Name                 string            `mapstructure:"name" json:"name"`
    BaseURL              string            `mapstructure:"baseurl" json:"baseurl"`
    AvailabilityEndpoint string            `mapstructure:"availabilityendpoint" json:"availabilityendpoint"`
    Digital              bool              `mapstructure:"digital" json:"digital"`
    Physical             bool              `mapstructure:"physical" json:"physical"`
    Location             string            `mapstructure:"location" json:"location"`
    BookingLinkSelector  string            `mapstructure:"bookinglinkselector" json:"bookinglinkselector"`
    LoginForm            LoginFormConfig   `mapstructure:"loginform" json:"loginform"`
    BookingForm          BookingFormConfig `mapstructure:"bookingform" json:"bookingform"`
    SuccessIndicator     string            `mapstructure:"successindicator" json:"successindicator"`
    Museums              map[string]Museum `mapstructure:"museums" json:"museums"`
    PreferredSlug        string            `mapstructure:"preferredslug" json:"preferredslug"`
}

type LoginFormConfig struct {
    UsernameField    string `mapstructure:"usernamefield" json:"usernamefield"`
    PasswordField    string `mapstructure:"passwordfield" json:"passwordfield"`
    SubmitButton     string `mapstructure:"submitbutton" json:"submitbutton"`
    CSRFSelector     string `mapstructure:"csrfselector" json:"csrfselector"`
    Username         string `mapstructure:"username" json:"username"`
    Password         string `mapstructure:"password" json:"password"`
    Email            string `mapstructure:"email" json:"email"`
    AuthIDSelector   string `mapstructure:"authidselector" json:"authidselector"`
    LoginURLSelector string `mapstructure:"loginurlselector" json:"loginurlselector"`
}

type BookingFormConfig struct {
    ActionURL  string            `mapstructure:"actionurl" json:"actionurl"`
    Fields     []FormFieldConfig `mapstructure:"fields" json:"fields"`
    EmailField string            `mapstructure:"emailfield" json:"emailfield"`
}

type FormFieldConfig struct {
    Name     string `mapstructure:"name" json:"name"`
    Type     string `mapstructure:"type" json:"type"`
    Value    string `mapstructure:"value" json:"value"`
    Selector string `mapstructure:"selector" json:"selector"`
}

type ScheduledRun struct {
    ID         string    `mapstructure:"id" json:"id"`
    SiteKey    string    `mapstructure:"sitekey" json:"sitekey"`
    MuseumSlug string    `mapstructure:"museumslug" json:"museumslug"`
    DropTime   time.Time `mapstructure:"droptime" json:"droptime"`
    Mode       string    `mapstructure:"mode" json:"mode"`
}

type AppConfig struct {
    Sites             map[string]Site `mapstructure:"sites" json:"sites"`
    ActiveSite        string          `mapstructure:"active_site" json:"active_site"`
    Mode              string          `mapstructure:"mode" json:"mode"`
    PreferredDays     []string        `mapstructure:"preferred_days" json:"preferred_days"`
    StrikeTime        string          `mapstructure:"strike_time" json:"strike_time"`
    CheckWindow       time.Duration   `mapstructure:"check_window" json:"check_window"`
    CheckInterval     time.Duration   `mapstructure:"check_interval" json:"check_interval"`
    PreWarmOffset     time.Duration   `mapstructure:"pre_warm_offset" json:"pre_warm_offset"`
    NtfyTopic         string          `mapstructure:"ntfy_topic" json:"ntfy_topic"`
    MaxWorkers        int             `mapstructure:"max_workers" json:"max_workers"`
    RequestJitter     time.Duration   `mapstructure:"request_jitter" json:"request_jitter"`
    MonthsToCheck     int             `mapstructure:"months_to_check" json:"months_to_check"`
    ScheduledRuns     []ScheduledRun  `mapstructure:"scheduled_runs" json:"scheduled_runs"`
    RestCycleChecks   int             `mapstructure:"rest_cycle_checks" json:"rest_cycle_checks"`
    RestCycleDuration time.Duration   `mapstructure:"rest_cycle_duration" json:"rest_cycle_duration"`
}
