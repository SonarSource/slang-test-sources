require_dependency 'email/message_builder'

class InviteMailer < ActionMailer::Base
  include Email::BuildEmailHelper

  class UserNotificationRenderer < ActionView::Base
    include UserNotificationsHelper
    include EmailHelper
  end

  def send_invite(invite)
    # Find the first topic they were invited to
    first_topic = invite.topics.order(:created_at).first

    # get invitee name (based on site setting)
    inviter_name = invite.invited_by.username
    if SiteSetting.enable_names && invite.invited_by.name.present?
      inviter_name = "#{invite.invited_by.name} (#{invite.invited_by.username})"
    end

    # If they were invited to a topic
    if first_topic.present?
      # get topic excerpt
      topic_excerpt = ""
      if first_topic.excerpt
        topic_excerpt = first_topic.excerpt.tr("\n", " ")
      end

      template = 'invite_mailer'
      if invite.custom_message.present?
        template = 'custom_invite_mailer'
      end

      topic_title = first_topic.try(:title)
      if SiteSetting.private_email?
        topic_title = I18n.t("system_messages.private_topic_title", id: first_topic.id)
        topic_excerpt = ""
      end

      build_email(invite.email,
                  template: template,
                  inviter_name: inviter_name,
                  site_domain_name: Discourse.current_hostname,
                  invite_link: "#{Discourse.base_url}/invites/#{invite.invite_key}",
                  topic_title: topic_title,
                  topic_excerpt: topic_excerpt,
                  site_description: SiteSetting.site_description,
                  site_title: SiteSetting.title,
                  user_custom_message: invite.custom_message)
    else
      template = 'invite_forum_mailer'
      if invite.custom_message.present?
        template = 'custom_invite_forum_mailer'
      end

      build_email(invite.email,
                  template: template,
                  inviter_name: inviter_name,
                  site_domain_name: Discourse.current_hostname,
                  invite_link: "#{Discourse.base_url}/invites/#{invite.invite_key}",
                  site_description: SiteSetting.site_description,
                  site_title: SiteSetting.title,
                  user_custom_message: invite.custom_message)
    end
  end

  def send_password_instructions(user)
    if user.present?
      email_token = user.email_tokens.create(email: user.email)
      build_email(user.email,
                  template: 'invite_password_instructions',
                  email_token: email_token.token)
    end
  end

end
