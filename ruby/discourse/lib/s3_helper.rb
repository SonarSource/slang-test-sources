require "aws-sdk-s3"

class S3Helper

  class SettingMissing < StandardError; end

  attr_reader :s3_bucket_name, :s3_bucket_folder_path

  def initialize(s3_bucket_name, tombstone_prefix = '', options = {})
    @s3_options = default_s3_options.merge(options)

    @s3_bucket_name, @s3_bucket_folder_path = begin
      raise Discourse::InvalidParameters.new("s3_bucket_name") if s3_bucket_name.blank?
      s3_bucket_name.downcase.split("/".freeze, 2)
    end

    @tombstone_prefix =
      if @s3_bucket_folder_path
        File.join(@s3_bucket_folder_path, tombstone_prefix)
      else
        tombstone_prefix
      end
  end

  def upload(file, path, options = {})
    path = get_path_for_s3_upload(path)
    obj = s3_bucket.object(path)
    obj.upload_file(file, options)
    path
  end

  def remove(s3_filename, copy_to_tombstone = false)
    bucket = s3_bucket

    # copy the file in tombstone
    if copy_to_tombstone && @tombstone_prefix.present?
      bucket
        .object(File.join(@tombstone_prefix, s3_filename))
        .copy_from(copy_source: File.join(@s3_bucket_name, get_path_for_s3_upload(s3_filename)))
    end

    # delete the file
    bucket.object(get_path_for_s3_upload(s3_filename)).delete
  rescue Aws::S3::Errors::NoSuchKey
  end

  # make sure we have a cors config for assets
  # otherwise we will have no fonts
  def ensure_cors!
    rule = nil

    begin
      rule = s3_resource.client.get_bucket_cors(
        bucket: @s3_bucket_name
      ).cors_rules&.first
    rescue Aws::S3::Errors::NoSuchCORSConfiguration
      # no rule
    end

    unless rule
      puts "installing CORS rule"

      s3_resource.client.put_bucket_cors(
        bucket: @s3_bucket_name,
        cors_configuration: {
          cors_rules: [{
            allowed_headers: ["Authorization"],
            allowed_methods: ["GET", "HEAD"],
            allowed_origins: ["*"],
            max_age_seconds: 3000
          }]
        }
      )
    end
  end

  def update_lifecycle(id, days, prefix: nil, tag: nil)

    filter = {}

    if prefix
      filter[:prefix] = prefix
    elsif tag
      filter[:tag] = tag
    end

    # cf. http://docs.aws.amazon.com/AmazonS3/latest/dev/object-lifecycle-mgmt.html
    rule = {
      id: id,
      status: "Enabled",
      expiration: { days: days },
      filter: filter
    }

    rules = []

    begin
      rules = s3_resource.client.get_bucket_lifecycle_configuration(bucket: @s3_bucket_name).rules
    rescue Aws::S3::Errors::NoSuchLifecycleConfiguration
      # skip trying to merge
    end

    # in the past we has a rule that was called purge-tombstone vs purge_tombstone
    # just go ahead and normalize for our bucket
    rules.delete_if do |r|
      r.id.gsub('_', '-') == id.gsub('_', '-')
    end

    rules << rule

    # normalize filter in rules, due to AWS library bug
    rules = rules.map do |r|
      r = r.to_h
      prefix = r.delete(:prefix)
      if prefix
        r[:filter] = { prefix: prefix }
      end
      r
    end

    s3_resource.client.put_bucket_lifecycle_configuration(
      bucket: @s3_bucket_name,
      lifecycle_configuration: {
        rules: rules
    })
  end

  def update_tombstone_lifecycle(grace_period)
    return if @tombstone_prefix.blank?
    update_lifecycle("purge_tombstone", grace_period, prefix: @tombstone_prefix)
  end

  def list(prefix = "")
    s3_bucket.objects(prefix: "#{@s3_bucket_folder_path}/#{prefix}")
  end

  def tag_file(key, tags)
    tag_array = []
    tags.each do |k, v|
      tag_array << { key: k.to_s, value: v.to_s }
    end

    s3_resource.client.put_object_tagging(
      bucket: @s3_bucket_name,
      key: key,
      tagging: {
        tag_set: tag_array
      }
    )
  end

  def self.s3_options(obj)
    opts = { region: obj.s3_region,
             endpoint: SiteSetting.s3_endpoint,
             force_path_style: SiteSetting.s3_force_path_style }

    unless obj.s3_use_iam_profile
      opts[:access_key_id] = obj.s3_access_key_id
      opts[:secret_access_key] = obj.s3_secret_access_key
    end

    opts
  end

  private

  def default_s3_options
    if SiteSetting.enable_s3_uploads?
      options = self.class.s3_options(SiteSetting)
      check_missing_site_options
      options
    elsif GlobalSetting.use_s3?
      self.class.s3_options(GlobalSetting)
    else
      {}
    end
  end

  def get_path_for_s3_upload(path)
    path = File.join(@s3_bucket_folder_path, path) if @s3_bucket_folder_path
    path
  end

  def s3_resource
    Aws::S3::Resource.new(@s3_options)
  end

  def s3_bucket
    bucket = s3_resource.bucket(@s3_bucket_name)
    bucket.create unless bucket.exists?
    bucket
  end

  def check_missing_site_options
    unless SiteSetting.s3_use_iam_profile
      raise SettingMissing.new("access_key_id") if SiteSetting.s3_access_key_id.blank?
      raise SettingMissing.new("secret_access_key") if SiteSetting.s3_secret_access_key.blank?
    end
  end
end
