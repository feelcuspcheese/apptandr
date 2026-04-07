
package config

import (
	"github.com/spf13/viper"
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
	Timezone   string    `mapstructure:"timezone" json:"timezone"`
}

type AppConfig struct {
	Sites             map[string]Site  `mapstructure:"sites" json:"sites"`
	ActiveSite        string           `mapstructure:"active_site" json:"active_site"`
	Mode              string           `mapstructure:"mode" json:"mode"`
	PreferredDays     []string         `mapstructure:"preferred_days" json:"preferred_days"`
	PreferredDates    []string         `mapstructure:"preferred_dates" json:"preferred_dates"` // Added for v1.1
	StrikeTime        string           `mapstructure:"strike_time" json:"strike_time"`
	CheckWindow       time.Duration    `mapstructure:"check_window" json:"check_window"`
	CheckInterval     time.Duration    `mapstructure:"check_interval" json:"check_interval"`
	PreWarmOffset     time.Duration    `mapstructure:"pre_warm_offset" json:"pre_warm_offset"`
	NtfyTopic         string           `mapstructure:"ntfy_topic" json:"ntfy_topic"`
	MaxWorkers        int              `mapstructure:"max_workers" json:"max_workers"`
	RequestJitter     time.Duration    `mapstructure:"request_jitter" json:"request_jitter"`
	MonthsToCheck     int              `mapstructure:"months_to_check" json:"months_to_check"`
	ScheduledRuns     []ScheduledRun   `mapstructure:"scheduled_runs" json:"scheduled_runs"`
	RestCycleChecks   int              `mapstructure:"rest_cycle_checks" json:"rest_cycle_checks"`
	RestCycleDuration time.Duration    `mapstructure:"rest_cycle_duration" json:"rest_cycle_duration"`
}

// LoadConfig loads configuration from a YAML file (used in Web/Standalone mode)
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

	// Set defaults if missing
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

// SaveConfig writes configuration back to YAML (used in Web/Standalone mode)
func SaveConfig(path string, cfg *AppConfig) error {
	viper.SetConfigFile(path)
	viper.Set("sites", cfg.Sites)
	viper.Set("active_site", cfg.ActiveSite)
	viper.Set("mode", cfg.Mode)
	viper.Set("preferred_days", cfg.PreferredDays)
	viper.Set("preferred_dates", cfg.PreferredDates) // Added for v1.1
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
