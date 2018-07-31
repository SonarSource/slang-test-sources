class DiskSpace

  extend ActionView::Helpers::NumberHelper

  DISK_SPACE_STATS_CACHE_KEY = 'disk_space_stats'.freeze
  DISK_SPACE_STATS_UPDATED_CACHE_KEY = 'disk_space_stats_updated'.freeze

  def self.uploads_used_bytes
    # used(uploads_path)
    # temporary (on our internal setup its just too slow to iterate)
    Upload.sum(:filesize).to_i
  end

  def self.uploads_free_bytes
    free(uploads_path)
  end

  def self.backups_used_bytes
    used(backups_path)
  end

  def self.backups_free_bytes
    free(backups_path)
  end

  def self.backups_path
    Backup.base_directory
  end

  def self.uploads_path
    "#{Rails.root}/public/uploads/#{RailsMultisite::ConnectionManagement.current_db}"
  end

  def self.stats
    {
      uploads_used: number_to_human_size(uploads_used_bytes),
      uploads_free: number_to_human_size(uploads_free_bytes),
      backups_used: number_to_human_size(backups_used_bytes),
      backups_free: number_to_human_size(backups_free_bytes)
    }
  end

  def self.reset_cached_stats
    $redis.del(DISK_SPACE_STATS_UPDATED_CACHE_KEY)
    $redis.del(DISK_SPACE_STATS_CACHE_KEY)
    Jobs.enqueue(:update_disk_space)
  end

  def self.cached_stats
    stats = $redis.get(DISK_SPACE_STATS_CACHE_KEY)
    updated_at = $redis.get(DISK_SPACE_STATS_UPDATED_CACHE_KEY)

    unless updated_at && (Time.now.to_i - updated_at.to_i) < 30.minutes
      Jobs.enqueue(:update_disk_space)
    end

    if stats
      JSON.parse(stats)
    end
  end

  protected

  def self.free(path)
    `df -Pk #{path} | awk 'NR==2 {print $4;}'`.to_i * 1024
  end

  def self.used(path)
    `du -s #{path}`.to_i * 1024
  end

end
