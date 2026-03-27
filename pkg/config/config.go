package config

import (
    "github.com/spf13/viper"
    "time"
)

type Museum struct {
    Name     string `mapstructure:"name"`
    Slug     string `mapstructure:"slug"`
    MuseumID string `mapstructure:"museum_id"`
}

type Site struct {
    Name                 string            `mapstructure:"name"`
    BaseURL              string            `mapstructure:"base_url"`
    AvailabilityEndpoint string            `mapstructure:"availability_endpoint"`
    Digital              bool              `mapstructure:"digital"`
    Physical             bool              `mapstructure:"physical"`
    Location             string            `mapstructure:"location"`
    BookingLinkSelector  string            `mapstructure:"booking_link_selector"`
    LoginForm            LoginFormConfig   `mapstructure:"login_form"`
    BookingForm          BookingFormConfig `mapstructure:"booking_form"`
    SuccessIndicator     string            `mapstructure:"success_indicator"`
    Museums              map[string]Museum `mapstructure:"museums"`
    PreferredSlug        string            `mapstructure:"preferred_slug"`
}

type LoginFormConfig struct {
    UsernameField       string `mapstructure:"username_field"`
    PasswordField       string `mapstructure:"password_field"`
    SubmitButton        string `mapstructure:"submit_button"`
    CSRFSelector        string `mapstructure:"csrf_token_selector"`
    Username            string `mapstructure:"username"`
    Password            string `mapstructure:"password"`
    Email               string `mapstructure:"email"`
    AuthIDSelector      string `mapstructure:"auth_id_selector"`
    LoginURLSelector    string `mapstructure:"login_url_selector"`
}

type BookingFormConfig struct {
    ActionURL  string            `mapstructure:"action_url"`
    Fields     []FormFieldConfig `mapstructure:"fields"`
    EmailField string            `mapstructure:"email_field"`
}

type FormFieldConfig struct {
    Name     string `mapstructure:"name"`
    Type     string `mapstructure:"type"`
    Value    string `mapstructure:"value"`
    Selector string `mapstructure:"selector"`
}

type ScheduledRun struct {
    ID         string    `mapstructure:"id"`
    SiteKey    string    `mapstructure:"site_key"`
    MuseumSlug string    `mapstructure:"museum_slug"`
    DropTime   time.Time `mapstructure:"drop_time"`
    Mode       string    `mapstructure:"mode"` // "alert" or "booking"
}

type AppConfig struct {
    Sites          map[string]Site   `mapstructure:"sites"`
    ActiveSite     string            `mapstructure:"active_site"`
    Mode           string            `mapstructure:"mode"`
    PreferredDays  []string          `mapstructure:"preferred_days"`
    StrikeTime     string            `mapstructure:"strike_time"`
    CheckWindow    time.Duration     `mapstructure:"check_window"`
    CheckInterval  time.Duration     `mapstructure:"check_interval"`
    PreWarmOffset  time.Duration     `mapstructure:"pre_warm_offset"`
    NtfyTopic      string            `mapstructure:"ntfy_topic"`
    MaxWorkers     int               `mapstructure:"max_workers"`
    RequestJitter  time.Duration     `mapstructure:"request_jitter"`
    MonthsToCheck  int               `mapstructure:"months_to_check"`
    ScheduledRuns  []ScheduledRun    `mapstructure:"scheduled_runs"`
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
    return viper.WriteConfig()
}
