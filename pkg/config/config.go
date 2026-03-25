package config

import (
    "github.com/spf13/viper"
    "time"
)

type SiteConfig struct {
    Name                 string            `mapstructure:"name"`
    BaseURL              string            `mapstructure:"base_url"`
    MuseumID             string            `mapstructure:"museum_id"`        // museum ID for availability endpoint
    Slug                 string            `mapstructure:"slug"`             // pass slug (e.g., "SAM", "Zoo")
    PassID               string            `mapstructure:"pass_id"`          // pass ID for booking URL
    Digital              bool              `mapstructure:"digital"`
    Physical             bool              `mapstructure:"physical"`
    Location             string            `mapstructure:"location"`         // location parameter (usually "0")
    TargetDate           string            `mapstructure:"target_date"`      // date to check (YYYY-MM-DD)
    AvailabilityEndpoint string            `mapstructure:"availability_endpoint"` // e.g., "/pass/availability/institution"
    BookingLinkSelector  string            `mapstructure:"booking_link_selector"` // CSS selector for available slots
    LoginURL             string            `mapstructure:"login_url"`        // not used directly, but kept for completeness
    LoginForm            LoginFormConfig   `mapstructure:"login_form"`
    BookingForm          BookingFormConfig `mapstructure:"booking_form"`
    SuccessIndicator     string            `mapstructure:"success_indicator"` // text that indicates success
}

type LoginFormConfig struct {
    UsernameField       string `mapstructure:"username_field"`
    PasswordField       string `mapstructure:"password_field"`
    SubmitButton        string `mapstructure:"submit_button"`
    CSRFSelector        string `mapstructure:"csrf_token_selector"`
    Username            string `mapstructure:"username"`
    Password            string `mapstructure:"password"`
    AuthIDSelector      string `mapstructure:"auth_id_selector"`   // hidden input name for auth_id
    LoginURLSelector    string `mapstructure:"login_url_selector"` // hidden input name for login_url
}

type BookingFormConfig struct {
    ActionURL  string            `mapstructure:"action_url"`
    Fields     []FormFieldConfig `mapstructure:"fields"`
    EmailField string            `mapstructure:"email_field"` // name of email input
}

type FormFieldConfig struct {
    Name     string `mapstructure:"name"`
    Type     string `mapstructure:"type"`
    Value    string `mapstructure:"value"`
    Selector string `mapstructure:"selector"`
}

type AppConfig struct {
    Site           SiteConfig    `mapstructure:"site"`
    Mode           string        `mapstructure:"mode"` // alert or booking
    PreferredDays  []string      `mapstructure:"preferred_days"`
    StrikeTime     string        `mapstructure:"strike_time"`
    CheckWindow    time.Duration `mapstructure:"check_window"`
    CheckInterval  time.Duration `mapstructure:"check_interval"`
    PreWarmOffset  time.Duration `mapstructure:"pre_warm_offset"`
    NtfyTopic      string        `mapstructure:"ntfy_topic"`
    MaxWorkers     int           `mapstructure:"max_workers"`
    RequestJitter  time.Duration `mapstructure:"request_jitter"`
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
    return &cfg, nil
}
