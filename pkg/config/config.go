type SiteConfig struct {
    Name                  string            `mapstructure:"name"`
    BaseURL               string            `mapstructure:"base_url"`
    AvailabilityEndpoint  string            `mapstructure:"availability_endpoint"`
    BookingLinkSelector   string            `mapstructure:"booking_link_selector"`
    LoginURL              string            `mapstructure:"login_url"`
    LoginForm             LoginFormConfig   `mapstructure:"login_form"`
    BookingForm           BookingFormConfig `mapstructure:"booking_form"`
    SuccessIndicator      string            `mapstructure:"success_indicator"`
    // New fields for libcal
    MuseumID              string            `mapstructure:"museum_id"`
    Digital               bool              `mapstructure:"digital"`
    Physical              bool              `mapstructure:"physical"`
    Location              string            `mapstructure:"location"`
}

type LoginFormConfig struct {
    UsernameField       string `mapstructure:"username_field"`
    PasswordField       string `mapstructure:"password_field"`
    SubmitButton        string `mapstructure:"submit_button"`
    CSRFSelector        string `mapstructure:"csrf_token_selector"`
    Username            string `mapstructure:"username"`
    Password            string `mapstructure:"password"`
    // For libcal login form extraction
    AuthIDSelector      string `mapstructure:"auth_id_selector"`    // hidden input name for auth_id
    LoginURLSelector    string `mapstructure:"login_url_selector"`  // hidden input name for login_url
}

type BookingFormConfig struct {
    ActionURL  string            `mapstructure:"action_url"`
    Fields     []FormFieldConfig `mapstructure:"fields"`
    // Additional config for libcal booking form
    EmailField string `mapstructure:"email_field"` // name of email input
}

type FormFieldConfig struct {
    Name     string `mapstructure:"name"`
    Type     string `mapstructure:"type"` // hidden, select, etc.
    Value    string `mapstructure:"value"` // optional static value
    Selector string `mapstructure:"selector"` // CSS selector for dynamic value extraction
}
