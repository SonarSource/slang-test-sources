class Auth::FacebookAuthenticator < Auth::Authenticator

  AVATAR_SIZE = 480

  def name
    "facebook"
  end

  def enabled?
    SiteSetting.enable_facebook_logins
  end

  def description_for_user(user)
    info = FacebookUserInfo.find_by(user_id: user.id)
    info&.email || info&.username || ""
  end

  def can_revoke?
    true
  end

  def revoke(user, skip_remote: false)
    info = FacebookUserInfo.find_by(user_id: user.id)
    raise Discourse::NotFound if info.nil?

    if skip_remote
      info.destroy!
      return true
    end

    response = Excon.delete(revoke_url(info.facebook_user_id))

    if response.status == 200
      info.destroy!
      return true
    end

    false
  end

  def revoke_url(fb_user_id)
    "https://graph.facebook.com/#{fb_user_id}/permissions?access_token=#{SiteSetting.facebook_app_id}|#{SiteSetting.facebook_app_secret}"
  end

  def can_connect_existing_user?
    true
  end

  def after_authenticate(auth_token, existing_account: nil)
    result = Auth::Result.new

    session_info = parse_auth_token(auth_token)
    facebook_hash = session_info[:facebook]

    result.email = email = session_info[:email]
    result.email_valid = !email.blank?
    result.name = facebook_hash[:name]

    result.extra_data = facebook_hash

    user_info = FacebookUserInfo.find_by(facebook_user_id: facebook_hash[:facebook_user_id])

    if existing_account && (user_info.nil? || existing_account.id != user_info.user_id)
      user_info.destroy! if user_info
      result.user = existing_account
      user_info = FacebookUserInfo.create!({ user_id: result.user.id }.merge(facebook_hash))
    else
      result.user = user_info&.user
    end

    if !result.user && !email.blank? && result.user = User.find_by_email(email)
      FacebookUserInfo.create!({ user_id: result.user.id }.merge(facebook_hash))
    end

    user_info.update_columns(facebook_hash) if user_info

    retrieve_avatar(result.user, result.extra_data)
    retrieve_profile(result.user, result.extra_data)

    if email.blank?
      UserHistory.create(
        action: UserHistory.actions[:facebook_no_email],
        details: "name: #{facebook_hash[:name]}, facebook_user_id: #{facebook_hash[:facebook_user_id]}"
      )
    end

    result
  end

  def after_create_account(user, auth)
    extra_data = auth[:extra_data]
    FacebookUserInfo.create!({ user_id: user.id }.merge(extra_data))

    retrieve_avatar(user, extra_data)
    retrieve_profile(user, extra_data)

    true
  end

  def register_middleware(omniauth)
    omniauth.provider :facebook,
           setup: lambda { |env|
             strategy = env["omniauth.strategy"]
              strategy.options[:client_id] = SiteSetting.facebook_app_id
              strategy.options[:client_secret] = SiteSetting.facebook_app_secret
              strategy.options[:info_fields] = 'gender,email,name,about,first_name,link,last_name,website,location'
           },
           scope: "email"
  end

  protected

  def parse_auth_token(auth_token)
    raw_info = auth_token["extra"]["raw_info"]
    info = auth_token["info"]

    email = auth_token["info"][:email]

    website = (info["urls"] && info["urls"]["Website"]) || nil

    {
      facebook: {
        facebook_user_id: auth_token["uid"],
        link: raw_info["link"],
        username: raw_info["username"],
        first_name: raw_info["first_name"],
        last_name: raw_info["last_name"],
        email: email,
        gender: raw_info["gender"],
        name: raw_info["name"],
        avatar_url: info["image"],
        location: info["location"],
        website: website,
        about_me: info["description"]
      },
      email: email,
      email_valid: true
    }
  end

  def retrieve_avatar(user, data)
    return unless user
    return if user.user_avatar.try(:custom_upload_id).present?

    if (avatar_url = data[:avatar_url]).present?
      url = "#{avatar_url}?height=#{AVATAR_SIZE}&width=#{AVATAR_SIZE}"
      Jobs.enqueue(:download_avatar_from_url, url: url, user_id: user.id, override_gravatar: false)
    end
  end

  def retrieve_profile(user, data)
    return unless user

    bio = data[:about_me] || data[:about]
    location = data[:location]
    website = data[:website]

    if bio || location || website
      profile = user.user_profile
      profile.bio_raw  = bio      unless profile.bio_raw.present?
      profile.location = location unless profile.location.present?
      profile.website  = website  unless profile.website.present?
      profile.save
    end
  end

end
