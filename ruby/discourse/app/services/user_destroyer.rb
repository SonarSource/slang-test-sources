require_dependency 'ip_addr'

# Responsible for destroying a User record
class UserDestroyer

  class PostsExistError < RuntimeError; end

  def initialize(actor)
    @actor = actor
    raise Discourse::InvalidParameters.new('acting user is nil') unless @actor && @actor.is_a?(User)
    @guardian = Guardian.new(actor)
  end

  # Returns false if the user failed to be deleted.
  # Returns a frozen instance of the User if the delete succeeded.
  def destroy(user, opts = {})
    raise Discourse::InvalidParameters.new('user is nil') unless user && user.is_a?(User)
    raise PostsExistError if !opts[:delete_posts] && user.posts.count != 0
    @guardian.ensure_can_delete_user!(user)

    User.transaction do

      Draft.where(user_id: user.id).delete_all
      QueuedPost.where(user_id: user.id).delete_all

      if opts[:delete_posts]
        user.posts.each do |post|
          # agree with flags
          PostAction.agree_flags!(post, @actor) if opts[:delete_as_spammer]

          # block all external urls
          if opts[:block_urls]
            post.topic_links.each do |link|
              next if link.internal
              next if Oneboxer.engine(link.url) != Onebox::Engine::WhitelistedGenericOnebox
              ScreenedUrl.watch(link.url, link.domain, ip_address: user.ip_address)&.record_match!
            end
          end

          PostDestroyer.new(@actor.staff? ? @actor : Discourse.system_user, post).destroy

          if post.topic && post.is_first_post?
            Topic.unscoped.where(id: post.topic_id).update_all(user_id: nil)
          end
        end
      end

      user.post_actions.each do |post_action|
        post_action.remove_act!(Discourse.system_user)
      end

      # keep track of emails used
      user_emails = user.user_emails.pluck(:email)

      user.destroy.tap do |u|
        if u
          if opts[:block_email]
            user_emails.each do |email|
              ScreenedEmail.block(email, ip_address: u.ip_address)&.record_match!
            end
          end

          if opts[:block_ip] && u.ip_address
            ScreenedIpAddress.watch(u.ip_address)&.record_match!
            if u.registration_ip_address && u.ip_address != u.registration_ip_address
              ScreenedIpAddress.watch(u.registration_ip_address)&.record_match!
            end
          end

          Post.unscoped.where(user_id: u.id).update_all(user_id: nil)

          # If this user created categories, fix those up:
          Category.where(user_id: u.id).each do |c|
            c.user_id = Discourse::SYSTEM_USER_ID
            c.save!
            if topic = Topic.unscoped.find_by(id: c.topic_id)
              topic.recover!
              topic.user_id = Discourse::SYSTEM_USER_ID
              topic.save!
            end
          end

          unless opts[:quiet]
            if @actor == user
              deleted_by = Discourse.system_user
              opts[:context] = I18n.t("staff_action_logs.user_delete_self", url: opts[:context])
            else
              deleted_by = @actor
            end
            StaffActionLogger.new(deleted_by).log_user_deletion(user, opts.slice(:context))
          end
          MessageBus.publish "/file-change", ["refresh"], user_ids: [u.id]
        end
      end
    end
  end

end
