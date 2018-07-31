require_dependency 'mem_info'

class AdminDashboardData
  include StatsCacheable

  GLOBAL_REPORTS ||= [
    'visits',
    'signups',
    'profile_views',
    'topics',
    'posts',
    'time_to_first_response',
    'topics_with_no_response',
    'likes',
    'flags',
    'bookmarks',
    'emails',
  ]

  PAGE_VIEW_REPORTS ||= ['page_view_total_reqs'] + ApplicationRequest.req_types.keys.select { |r| r =~ /^page_view_/ && r !~ /mobile/ }.map { |r| r + "_reqs" }

  PRIVATE_MESSAGE_REPORTS ||= [
    'user_to_user_private_messages',
    'user_to_user_private_messages_with_replies',
    'system_private_messages',
    'notify_moderators_private_messages',
    'notify_user_private_messages',
    'moderator_warning_private_messages',
  ]

  HTTP_REPORTS ||= ApplicationRequest.req_types.keys.select { |r| r =~ /^http_/ }.map { |r| r + "_reqs" }.sort

  USER_REPORTS ||= ['users_by_trust_level']

  MOBILE_REPORTS ||= ['mobile_visits'] + ApplicationRequest.req_types.keys.select { |r| r =~ /mobile/ }.map { |r| r + "_reqs" }

  def self.add_problem_check(*syms, &blk)
    @problem_syms.push(*syms) if syms
    @problem_blocks << blk if blk
  end
  class << self; attr_reader :problem_syms, :problem_blocks, :problem_messages; end

  def initialize(opts = {})
    @opts = opts
  end

  def problems
    problems = []
    AdminDashboardData.problem_syms.each do |sym|
      problems << send(sym)
    end
    AdminDashboardData.problem_blocks.each do |blk|
      problems << instance_exec(&blk)
    end
    AdminDashboardData.problem_messages.each do |i18n_key|
      problems << AdminDashboardData.problem_message_check(i18n_key)
    end
    problems.compact!

    if problems.empty?
      self.class.clear_problems_started
    else
      self.class.set_problems_started
    end

    problems
  end

  def self.problems_started_key
    "dash-problems-started-at"
  end

  def self.set_problems_started
    existing_time = $redis.get(problems_started_key)
    $redis.setex(problems_started_key, 14.days.to_i, existing_time || Time.zone.now.to_s)
  end

  def self.clear_problems_started
    $redis.del problems_started_key
  end

  def self.problems_started_at
    s = $redis.get(problems_started_key)
    s ? Time.zone.parse(s) : nil
  end

  # used for testing
  def self.reset_problem_checks
    @problem_syms = []
    @problem_blocks = []

    @problem_messages = [
      'dashboard.bad_favicon_url',
      'dashboard.poll_pop3_timeout',
      'dashboard.poll_pop3_auth_error'
    ]

    add_problem_check :rails_env_check, :host_names_check, :force_https_check,
                      :ram_check, :google_oauth2_config_check,
                      :facebook_config_check, :twitter_config_check,
                      :github_config_check, :s3_config_check, :image_magick_check,
                      :failing_emails_check,
                      :subfolder_ends_in_slash_check,
                      :pop3_polling_configuration, :email_polling_errored_recently

    add_problem_check do
      sidekiq_check || queue_size_check
    end
  end
  reset_problem_checks

  def self.fetch_stats
    AdminDashboardData.new.as_json
  end

  def self.stats_cache_key
    'dash-stats'
  end

  def self.fetch_problems(opts = {})
    AdminDashboardData.new(opts).problems
  end

  def self.problem_message_check(i18n_key)
    $redis.get(problem_message_key(i18n_key)) ? I18n.t(i18n_key) : nil
  end

  def self.add_problem_message(i18n_key, expire_seconds = nil)
    if expire_seconds.to_i > 0
      $redis.setex problem_message_key(i18n_key), expire_seconds.to_i, 1
    else
      $redis.set problem_message_key(i18n_key), 1
    end
  end

  def self.clear_problem_message(i18n_key)
    $redis.del problem_message_key(i18n_key)
  end

  def self.problem_message_key(i18n_key)
    "admin-problem:#{i18n_key}"
  end

  def as_json(_options = nil)
    @json ||= {
      global_reports: AdminDashboardData.reports(GLOBAL_REPORTS),
      page_view_reports: AdminDashboardData.reports(PAGE_VIEW_REPORTS),
      private_message_reports: AdminDashboardData.reports(PRIVATE_MESSAGE_REPORTS),
      http_reports: AdminDashboardData.reports(HTTP_REPORTS),
      user_reports: AdminDashboardData.reports(USER_REPORTS),
      mobile_reports: AdminDashboardData.reports(MOBILE_REPORTS),
      admins: User.admins.count,
      moderators: User.moderators.count,
      suspended: User.suspended.count,
      silenced: User.silenced.count,
      top_referrers: IncomingLinksReport.find('top_referrers').as_json,
      top_traffic_sources: IncomingLinksReport.find('top_traffic_sources').as_json,
      top_referred_topics: IncomingLinksReport.find('top_referred_topics').as_json,
      updated_at: Time.zone.now.as_json
    }
  end

  def self.reports(source)
    source.map { |type| Report.find(type).as_json }
  end

  def rails_env_check
    I18n.t("dashboard.rails_env_warning", env: Rails.env) unless Rails.env.production?
  end

  def host_names_check
    I18n.t("dashboard.host_names_warning") if ['localhost', 'production.localhost'].include?(Discourse.current_hostname)
  end

  def sidekiq_check
    last_job_performed_at = Jobs.last_job_performed_at
    I18n.t('dashboard.sidekiq_warning') if Jobs.queued > 0 && (last_job_performed_at.nil? || last_job_performed_at < 2.minutes.ago)
  end

  def queue_size_check
    queue_size = Jobs.queued
    I18n.t('dashboard.queue_size_warning', queue_size: queue_size) unless queue_size < 100_000
  end

  def ram_check
    I18n.t('dashboard.memory_warning') if MemInfo.new.mem_total && MemInfo.new.mem_total < 1_000_000
  end

  def google_oauth2_config_check
    I18n.t('dashboard.google_oauth2_config_warning') if SiteSetting.enable_google_oauth2_logins && (SiteSetting.google_oauth2_client_id.blank? || SiteSetting.google_oauth2_client_secret.blank?)
  end

  def facebook_config_check
    I18n.t('dashboard.facebook_config_warning') if SiteSetting.enable_facebook_logins && (SiteSetting.facebook_app_id.blank? || SiteSetting.facebook_app_secret.blank?)
  end

  def twitter_config_check
    I18n.t('dashboard.twitter_config_warning') if SiteSetting.enable_twitter_logins && (SiteSetting.twitter_consumer_key.blank? || SiteSetting.twitter_consumer_secret.blank?)
  end

  def github_config_check
    I18n.t('dashboard.github_config_warning') if SiteSetting.enable_github_logins && (SiteSetting.github_client_id.blank? || SiteSetting.github_client_secret.blank?)
  end

  def s3_config_check
    # if set via global setting it is validated during the `use_s3?` call
    if !GlobalSetting.use_s3?
      bad_keys = (SiteSetting.s3_access_key_id.blank? || SiteSetting.s3_secret_access_key.blank?) && !SiteSetting.s3_use_iam_profile

      return I18n.t('dashboard.s3_config_warning') if SiteSetting.enable_s3_uploads && (bad_keys || SiteSetting.s3_upload_bucket.blank?)
      return I18n.t('dashboard.s3_backup_config_warning') if SiteSetting.enable_s3_backups && (bad_keys || SiteSetting.s3_backup_bucket.blank?)
    end
    nil
  end

  def image_magick_check
    I18n.t('dashboard.image_magick_warning') if SiteSetting.create_thumbnails && !system("command -v convert >/dev/null;")
  end

  def failing_emails_check
    num_failed_jobs = Jobs.num_email_retry_jobs
    I18n.t('dashboard.failing_emails_warning', num_failed_jobs: num_failed_jobs) if num_failed_jobs > 0
  end

  def subfolder_ends_in_slash_check
    I18n.t('dashboard.subfolder_ends_in_slash') if Discourse.base_uri =~ /\/$/
  end

  def pop3_polling_configuration
    POP3PollingEnabledSettingValidator.new.error_message if SiteSetting.pop3_polling_enabled
  end

  def email_polling_errored_recently
    errors = Jobs::PollMailbox.errors_in_past_24_hours
    I18n.t('dashboard.email_polling_errored_recently', count: errors) if errors > 0
  end

  def missing_mailgun_api_key
    return unless SiteSetting.reply_by_email_enabled
    return unless ActionMailer::Base.smtp_settings[:address]["smtp.mailgun.org"]
    return unless SiteSetting.mailgun_api_key.blank?
    I18n.t('dashboard.missing_mailgun_api_key')
  end

  def force_https_check
    return unless @opts[:check_force_https]
    I18n.t('dashboard.force_https_warning') unless SiteSetting.force_https
  end

end
