*   Allows configurable attribute name for `#has_secure_password`. This
    still defaults to an attribute named 'password', causing no breaking
    change. There is a new method `#authenticate_XXX` where XXX is the
    configured attribute name, making the existing `#authenticate` now an
    alias for this when the attribute is the default 'password'.
    Example:

        class User < ActiveRecord::Base
          has_secure_password :recovery_password, validations: false
        end

        user = User.new()
        user.recovery_password = "42password"
        user.recovery_password_digest # => "$2a$04$iOfhwahFymCs5weB3BNH/uX..."
        user.authenticate_recovery_password('42password') # => user

     *Unathi Chonco*

*   Add `config.active_model.i18n_full_message` in order to control whether
    the `full_message` error format can be overridden at the attribute or model
    level in the locale files. This is `false` by default.

    *Martin Larochelle*

*   Rails 6 requires Ruby 2.4.1 or newer.

    *Jeremy Daer*


Please check [5-2-stable](https://github.com/rails/rails/blob/5-2-stable/activemodel/CHANGELOG.md) for previous changes.
