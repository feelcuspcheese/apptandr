package config

import (
    "github.com/spf13/viper"
    "time"
)

type Museum struct {
    Name     string `mapstructure:"name"`
    Slug     string `mapstructure:"slug"`
    MuseumID string `mapstructure:"museumid"`
}

type Site struct {
    Name                 string            `mapstructure:"name"`
    BaseURL              string            `mapstructure:"baseurl"`
    AvailabilityEndpoint string            `mapstructure:"availabilityendpoint"`
    Digital              bool              `mapstructure:"digital"`
    Physical             bool              `mapstructure:"physical"`
    Location             string            `mapstructure:"location"`
    BookingLinkSelector  string            `mapstructure:"bookinglinkselector"`
    LoginForm            LoginFormConfig   `mapstructure:"loginform"`
    BookingForm          BookingFormConfig `mapstructure:"bookingform"`
    SuccessIndicator     string            `mapstructure:"successindicator"`
    Museums              map[string]Museum `mapstructure:"museums"`
    PreferredSlug        string            `mapstructure:"preferredslug"`
}

type LoginFormConfig struct {
    UsernameField    string `mapstructure:"usernamefield"`
    PasswordField    string `mapstructure:"passwordfield"`
    SubmitButton     string `mapstructure:"submitbutton"`
    CSRFSelector     string `mapstructure:"csrfselector"`
    Username         string `mapstructure:"username"`
    Password         string `mapstructure:"password"`
    Email            string `mapstructure:"email"`
    AuthIDSelector   string `mapstructure:"authidselector"`
    LoginURLSelector string `mapstructure:"loginurlselector"`
}

type BookingFormConfig struct {
    ActionURL  string            `mapstructure:"actionurl"`
    Fields     []FormFieldConfig `mapstructure:"fields"`
    EmailField string            `mapstructure:"emailfield"`
}

type FormFieldConfig struct {
    Name     string `mapstructure:"name"`
    Type     string `mapstructure:"type"`
    Value    string `mapstructure:"value"`
    Selector string `mapstructure:"selector"`
}

type ScheduledRun struct {
    ID         string    `mapstructure:"id"`
    SiteKey    string    `mapstructure:"sitekey"`
    MuseumSlug string    `mapstructure:"museumslug"`
    DropTime   time.Time `mapstructure:"droptime"`
    Mode       string    `mapstructure:"mode"`
}

type AppConfig struct {
    Sites             map[string]Site `mapstructure:"sites"`
    ActiveSite        string          `mapstructure:"active_site"`
    Mode              string          `mapstructure:"mode"`
    PreferredDays     []string        `mapstructure:"preferred_days"`
    StrikeTime        string          `mapstructure:"strike_time"`
    CheckWindow       time.Duration   `mapstructure:"check_window"`
    CheckInterval     time.Duration   `mapstructure:"check_interval"`
    PreWarmOffset     time.Duration   `mapstructure:"pre_warm_offset"`
    NtfyTopic         string          `mapstructure:"ntfy_topic"`
    MaxWorkers        int             `mapstructure:"max_workers"`
    RequestJitter     time.Duration   `mapstructure:"request_jitter"`
    MonthsToCheck     int             `mapstructure:"months_to_check"`
    ScheduledRuns     []ScheduledRun  `mapstructure:"scheduled_runs"`
    RestCycleChecks   int             `mapstructure:"rest_cycle_checks"`
    RestCycleDuration time.Duration   `mapstructure:"rest_cycle_duration"`
}

func LoadConfig(path string) (*AppConfig, error) {
    viper.SetConfigFile(path)
    viper.AutomaticEnv()
    if err := viper.ReadInConfig(); err != nil {
        return nil, err
    }
    var cfg AppConfig
    if err := viper.Unmarshal(&cfg); err != nil {
        return nil, err
    }

    if cfg.MonthsToCheck == 0 {
        cfg.MonthsToCheck = 2
    }
    if cfg.PreWarmOffset == 0 {
        cfg.PreWarmOffset = 30 * time.Second
    }
    if cfg.RestCycleChecks == 0 {
        cfg.RestCycleChecks = 20
    }
    if cfg.RestCycleDuration == 0 {
        cfg.RestCycleDuration = 3 * time.Second
    }
    return &cfg, nil
}

func SaveConfig(path string, cfg *AppConfig) error {
    viper.SetConfigFile(path)
    viper.Set("sites", cfg.Sites)
    viper.Set("active_site", cfg.ActiveSite)
    viper.Set("mode", cfg.Mode)
    viper.Set("preferred_days", cfg.PreferredDays)
    viper.Set("strike_time", cfg.StrikeTime)
    viper.Set("check_window", cfg.CheckWindow)
    viper.Set("check_interval", cfg.CheckInterval)
    viper.Set("pre_warm_offset", cfg.PreWarmOffset)
    viper.Set("ntfy_topic", cfg.NtfyTopic)
    viper.Set("max_workers", cfg.MaxWorkers)
    viper.Set("request_jitter", cfg.RequestJitter)
    viper.Set("months_to_check", cfg.MonthsToCheck)
    viper.Set("scheduled_runs", cfg.ScheduledRuns)
    viper.Set("rest_cycle_checks", cfg.RestCycleChecks)
    viper.Set("rest_cycle_duration", cfg.RestCycleDuration)
    return viper.WriteConfig()
}
