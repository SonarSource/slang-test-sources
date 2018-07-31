class EmailBackupToken

  def self.key(user_id)
    "email-backup-token:#{user_id}"
  end

  def self.generate
    SecureRandom.hex
  end

  def self.set(user_id)
    token = self.generate
    $redis.setex self.key(user_id), 1.day.to_i, token
    token
  end

  def self.get(user_id)
    $redis.get self.key(user_id)
  end

  def self.del(user_id)
    $redis.del self.key(user_id)
  end

  def self.compare(user_id, token)
    token == self.get(user_id)
  end
end
