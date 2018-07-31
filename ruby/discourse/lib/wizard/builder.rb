require_dependency 'introduction_updater'
require_dependency 'emoji_set_site_setting'

class Wizard
  class Builder

    def initialize(user)
      @wizard = Wizard.new(user)
    end

    def build
      return @wizard unless SiteSetting.wizard_enabled? && @wizard.user.try(:staff?)

      @wizard.append_step('locale') do |step|
        step.banner = "welcome.png"

        languages = step.add_field(id: 'default_locale',
                                   type: 'dropdown',
                                   required: true,
                                   value: SiteSetting.default_locale)

        LocaleSiteSetting.values.each do |locale|
          languages.add_choice(locale[:value], label: locale[:name])
        end

        step.on_update do |updater|
          old_locale = SiteSetting.default_locale
          updater.apply_setting(:default_locale)
          updater.refresh_required = true if old_locale != updater.fields[:default_locale]
        end
      end

      @wizard.append_step('forum-title') do |step|
        step.add_field(id: 'title', type: 'text', required: true, value: SiteSetting.title)
        step.add_field(id: 'site_description', type: 'text', required: true, value: SiteSetting.site_description)

        step.on_update do |updater|
          updater.ensure_changed(:title)

          if updater.errors.blank?
            updater.apply_settings(:title, :site_description)
          end
        end
      end

      @wizard.append_step('introduction') do |step|
        introduction = IntroductionUpdater.new(@wizard.user)

        step.add_field(id: 'welcome', type: 'textarea', required: true, value: introduction.get_summary)

        step.on_update do |updater|
          value = updater.fields[:welcome].strip

          if value.index("\n")
            updater.errors.add(:welcome, I18n.t("wizard.step.introduction.fields.welcome.one_paragraph"))
          else
            introduction.update_summary(value)
          end
        end
      end

      @wizard.append_step('privacy') do |step|
        locked = SiteSetting.login_required? && SiteSetting.invite_only?
        privacy = step.add_field(id: 'privacy',
                                 type: 'radio',
                                 required: true,
                                 value: locked ? 'restricted' : 'open')
        privacy.add_choice('open', icon: 'unlock')
        privacy.add_choice('restricted', icon: 'lock')

        step.on_update do |updater|
          updater.update_setting(:login_required, updater.fields[:privacy] == 'restricted')
          updater.update_setting(:invite_only, updater.fields[:privacy] == 'restricted')
        end
      end

      @wizard.append_step('contact') do |step|
        step.add_field(id: 'contact_email', type: 'text', required: true, value: SiteSetting.contact_email)
        step.add_field(id: 'contact_url', type: 'text', value: SiteSetting.contact_url)

        username = SiteSetting.site_contact_username
        username = Discourse.system_user.username if username.blank?
        contact = step.add_field(id: 'site_contact', type: 'dropdown', value: username)

        User.human_users.where(admin: true).pluck(:username).each do |c|
          contact.add_choice(c) unless reserved_usernames.include?(c.downcase)
        end
        contact.add_choice(Discourse.system_user.username)

        step.on_update do |updater|
          updater.apply_settings(:contact_email, :contact_url)
          updater.update_setting(:site_contact_username, updater.fields[:site_contact])
        end
      end

      @wizard.append_step('corporate') do |step|
        step.add_field(id: 'company_short_name', type: 'text', value: SiteSetting.company_short_name)
        step.add_field(id: 'company_full_name', type: 'text', value: SiteSetting.company_full_name)
        step.add_field(id: 'company_domain', type: 'text', value: SiteSetting.company_domain)

        step.on_update do |updater|

          tos_post = Post.where(topic_id: SiteSetting.tos_topic_id, post_number: 1).first
          if tos_post.present?
            raw = tos_post.raw.dup

            replace_company(updater, raw, 'company_full_name')
            replace_company(updater, raw, 'company_short_name')
            replace_company(updater, raw, 'company_domain')

            revisor = PostRevisor.new(tos_post)
            revisor.revise!(@wizard.user, raw: raw)
          end

          updater.apply_settings(:company_short_name, :company_full_name, :company_domain)
        end
      end

      @wizard.append_step('colors') do |step|
        default_theme = Theme.find_by(id: SiteSetting.default_theme_id)
        scheme_id = default_theme&.color_scheme&.base_scheme_id || 'Light'

        themes = step.add_field(id: 'theme_previews', type: 'component', required: true, value: scheme_id)
        ColorScheme.base_color_scheme_colors.each do |t|
          with_hash = t[:colors].dup
          with_hash.map { |k, v| with_hash[k] = "##{v}" }
          themes.add_choice(t[:id], data: { colors: with_hash })
        end

        step.on_update do |updater|
          scheme_name = updater.fields[:theme_previews] || 'Light'
          name = I18n.t("color_schemes.#{scheme_name.downcase.gsub(' ', '_')}_theme_name")

          theme = nil
          scheme = ColorScheme.find_by(base_scheme_id: scheme_name, via_wizard: true)
          scheme ||= ColorScheme.create_from_base(name: name, via_wizard: true, base_scheme_id: scheme_name)
          themes = Theme.where(color_scheme_id: scheme.id).order(:id).to_a
          theme = themes.find(&:default?)
          theme ||= themes.first

          theme ||= Theme.create(name: name, user_id: @wizard.user.id, color_scheme_id: scheme.id)
          theme.set_default!
        end
      end

      @wizard.append_step('logos') do |step|
        step.add_field(id: 'logo_url', type: 'image', value: SiteSetting.logo_url)
        step.add_field(id: 'logo_small_url', type: 'image', value: SiteSetting.logo_small_url)

        step.on_update do |updater|
          updater.apply_settings(:logo_url, :logo_small_url)
        end
      end

      @wizard.append_step('icons') do |step|
        step.add_field(id: 'favicon_url', type: 'image', value: SiteSetting.favicon_url)
        step.add_field(id: 'apple_touch_icon_url', type: 'image', value: SiteSetting.apple_touch_icon_url)

        step.on_update do |updater|
          updater.apply_settings(:favicon_url)

          if updater.fields[:apple_touch_icon_url] != SiteSetting.apple_touch_icon_url
            upload = Upload.find_by_url(updater.fields[:apple_touch_icon_url])
            dimensions = 180 # for apple touch icon
            if upload && upload.width > dimensions && upload.height > dimensions
              updater.update_setting(:large_icon_url, updater.fields[:apple_touch_icon_url])

              apple_touch_icon_optimized = OptimizedImage.create_for(upload, dimensions, dimensions, filename: upload.original_filename)
              original_file = File.new(Discourse.store.path_for(apple_touch_icon_optimized)) rescue nil
              if original_file
                apple_touch_icon_upload = UploadCreator.new(original_file, upload.original_filename).create_for(@wizard.user.id)
                updater.update_setting(:apple_touch_icon_url, apple_touch_icon_upload.url)
              end
              apple_touch_icon_optimized.destroy! if apple_touch_icon_optimized.present?
            else
              updater.apply_settings(:apple_touch_icon_url)
            end
          end
        end
      end

      @wizard.append_step('homepage') do |step|

        current = SiteSetting.top_menu.starts_with?("categories") ? SiteSetting.desktop_category_page_style : "latest"

        style = step.add_field(id: 'homepage_style', type: 'dropdown', required: true, value: current)
        style.add_choice('latest')
        CategoryPageStyle.values.each do |page|
          style.add_choice(page[:value])
        end

        step.add_field(id: 'homepage_preview', type: 'component')

        step.on_update do |updater|
          if updater.fields[:homepage_style] == 'latest'
            top_menu = "latest|new|unread|top|categories"
          else
            top_menu = "categories|latest|new|unread|top"
            updater.update_setting(:desktop_category_page_style, updater.fields[:homepage_style])
          end
          updater.update_setting(:top_menu, top_menu)
        end
      end

      @wizard.append_step('emoji') do |step|
        sets = step.add_field(id: 'emoji_set',
                              type: 'radio',
                              required: true,
                              value: SiteSetting.emoji_set)

        emoji = ["smile", "+1", "tada", "poop"]

        EmojiSetSiteSetting.values.each do |set|
          imgs = emoji.map do |e|
            "<img src='#{Discourse.base_uri}/images/emoji/#{set[:value]}/#{e}.png'>"
          end

          sets.add_choice(set[:value],             label: I18n.t("js.#{set[:name]}"),
                                                   extra_label: "<span class='emoji-preview'>#{imgs.join}</span>")

          step.on_update do |updater|
            updater.apply_settings(:emoji_set)
          end
        end
      end

      @wizard.append_step('invites') do |step|

        staff_count = User.staff.human_users.where('username_lower not in (?)', reserved_usernames).count
        step.add_field(id: 'staff_count', type: 'component', value: staff_count)

        step.add_field(id: 'invite_list', type: 'component')

        step.on_update do |updater|
          users = JSON.parse(updater.fields[:invite_list])

          users.each do |u|
            args = {}
            args[:moderator] = true if u['role'] == 'moderator'
            begin
              Invite.create_invite_by_email(u['email'], @wizard.user, args)
            rescue => e
              updater.errors.add(:invite_list, e.message.concat("<br>"))
            end
          end
        end
      end

      DiscourseEvent.trigger(:build_wizard, @wizard)

      @wizard.append_step('finished') do |step|
        step.banner = "finished.png"
      end
      @wizard
    end

  protected

    def replace_company(updater, raw, field_name)
      old_value = SiteSetting.send(field_name)
      old_value = field_name if old_value.blank?

      new_value = updater.fields[field_name.to_sym]
      new_value = field_name if new_value.blank?

      raw.gsub!(old_value, new_value)
    end

    def reserved_usernames
      @reserved_usernames ||= SiteSetting.defaults[:reserved_usernames].split('|')
    end
  end
end
