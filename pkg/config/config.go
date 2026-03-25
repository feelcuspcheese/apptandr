package config

import (
    "github.com/spf13/viper"
    "time"
)

type SiteConfig struct {
    Name                 string            `mapstructure:"name"`
    BaseURL              string            `mapstructure:"base_url"`
    AvailabilityEndpoint string            `mapstructure:"availability_endpoint"`
    BookingLinkSelector  string            `mapstructure:"booking_link_selector"`
    LoginURL             string            `mapstructure:"login_url"`
    LoginForm            LoginFormConfig   `mapstructure:"login_form"`
    BookingForm          BookingFormConfig `mapstructure:"booking_form"`
    SuccessIndicator     string            `mapstructure:"success_indicator"`
}

type LoginFormConfig struct {
    UsernameField       string `mapstructure:"username_field"`
    PasswordField       string `mapstructure:"password_field"`
    SubmitButton        string `mapstructure:"submit_button"`
    CSRFSelector        string `mapstructure:"csrf_token_selector"`
    Username            string `mapstructure:"username"`
    Password            string `mapstructure:"password"`
}

type BookingFormConfig struct {
    ActionURL  string            `mapstructure:"action_url"`
    Fields     []FormFieldConfig `mapstructure:"fields"`
}

type FormFieldConfig struct {
    Name  string `mapstructure:"name"`
    Type  string `mapstructure:"type"` // hidden, select, etc.
    Value string `mapstructure:"value"` // optional static value
    Selector string `mapstructure:"selector"` // CSS selector for dynamic value extraction
}

type AppConfig struct {
    Site          SiteConfig   `mapstructure:"site"`
    Mode          string       `mapstructure:"mode"` // alert or booking
    PreferredDays []string     `mapstructure:"preferred_days"`
    StrikeTime    string       `mapstructure:"strike_time"`
    CheckWindow   time.Duration `mapstructure:"check_window"`   // e.g., "1m", "30s"
    CheckInterval time.Duration `mapstructure:"check_interval"` // e.g., "2s", "500ms"
    NtfyTopic     string       `mapstructure:"ntfy_topic"`
    MaxWorkers    int          `mapstructure:"max_workers"`
    RequestJitter time.Duration `mapstructure:"request_jitter"`
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
