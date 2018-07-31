class GroupsController < ApplicationController
  requires_login only: [
    :set_notifications,
    :mentionable,
    :messageable,
    :update,
    :histories,
    :request_membership,
    :search,
    :new
  ]

  skip_before_action :preload_json, :check_xhr, only: [:posts_feed, :mentions_feed]
  skip_before_action :check_xhr, only: [:show]

  TYPE_FILTERS = {
    my: Proc.new { |groups, user|
      raise Discourse::NotFound unless user
      Group.member_of(groups, user)
    },
    owner: Proc.new { |groups, user|
      raise Discourse::NotFound unless user
      Group.owner_of(groups, user)
    },
    public: Proc.new { |groups|
      groups.where(public_admission: true, automatic: false)
    },
    close: Proc.new { |groups|
      groups.where(
        public_admission: false,
        automatic: false
      )
    },
    automatic: Proc.new { |groups|
      groups.where(automatic: true)
    }
  }

  def index
    unless SiteSetting.enable_group_directory? || current_user&.staff?
      raise Discourse::InvalidAccess.new(:enable_group_directory)
    end

    page_size = 30
    page = params[:page]&.to_i || 0
    order = %w{name user_count}.delete(params[:order])
    dir = params[:asc] ? 'ASC' : 'DESC'
    groups = Group.visible_groups(current_user, order ? "#{order} #{dir}" : nil)

    if (filter = params[:filter]).present?
      groups = Group.search_groups(filter, groups: groups)
    end

    type_filters = TYPE_FILTERS.keys

    if username = params[:username]
      groups = TYPE_FILTERS[:my].call(groups, User.find_by_username(username))
      type_filters = type_filters - [:my, :owner]
    end

    unless guardian.is_staff?
      # hide automatic groups from all non stuff to de-clutter page
      groups = groups.where("automatic IS FALSE OR groups.id = #{Group::AUTO_GROUPS[:moderators]}")
      type_filters.delete(:automatic)
    end

    if Group.preloaded_custom_field_names.present?
      Group.preload_custom_fields(groups, Group.preloaded_custom_field_names)
    end

    if type = params[:type]&.to_sym
      callback = TYPE_FILTERS[type]
      if !callback
        raise Discourse::InvalidParameters.new(:type)
      end
      groups = callback.call(groups, current_user)
    end

    if current_user
      group_users = GroupUser.where(group: groups, user: current_user)
      user_group_ids = group_users.pluck(:group_id)
      owner_group_ids = group_users.where(owner: true).pluck(:group_id)
    else
      type_filters = type_filters - [:my, :owner]
    end

    count = groups.count
    groups = groups.offset(page * page_size).limit(page_size)

    render_json_dump(
      groups: serialize_data(groups,
        BasicGroupSerializer,
        user_group_ids: user_group_ids || [],
        owner_group_ids: owner_group_ids || []
      ),
      extras: {
        type_filters: type_filters
      },
      total_rows_groups: count,
      load_more_groups: groups_path(page: page + 1, type: type),
    )
  end

  def show
    respond_to do |format|
      group = find_group(:id)

      format.html do
        @title = group.full_name.present? ? group.full_name.capitalize : group.name
        @description_meta = group.bio_cooked.present? ? PrettyText.excerpt(group.bio_cooked, 300) : @title
        render :show
      end

      format.json do
        groups = Group.visible_groups(current_user)

        if !guardian.is_staff?
          groups = groups.where(automatic: false)
        end

        render_json_dump(
          group: serialize_data(group, GroupShowSerializer, root: nil),
          extras: {
            visible_group_names: groups.pluck(:name)
          }
        )
      end
    end
  end

  def new
  end

  def edit
  end

  def update
    group = Group.find(params[:id])
    guardian.ensure_can_edit!(group) unless current_user.admin

    if group.update(group_params(automatic: group.automatic))
      GroupActionLogger.new(current_user, group).log_change_group_settings
      render json: success_json
    else
      render_json_error(group)
    end
  end

  def posts
    group = find_group(:group_id)
    posts = group.posts_for(
      guardian,
      params.permit(:before_post_id, :category_id)
    ).limit(20)
    render_serialized posts.to_a, GroupPostSerializer
  end

  def posts_feed
    group = find_group(:group_id)
    @posts = group.posts_for(
      guardian,
      params.permit(:before_post_id, :category_id)
    ).limit(50)
    @title = "#{SiteSetting.title} - #{I18n.t("rss_description.group_posts", group_name: group.name)}"
    @link = Discourse.base_url
    @description = I18n.t("rss_description.group_posts", group_name: group.name)
    render 'posts/latest', formats: [:rss]
  end

  def mentions
    raise Discourse::NotFound unless SiteSetting.enable_mentions?
    group = find_group(:group_id)
    posts = group.mentioned_posts_for(
      guardian,
      params.permit(:before_post_id, :category_id)
    ).limit(20)
    render_serialized posts.to_a, GroupPostSerializer
  end

  def mentions_feed
    raise Discourse::NotFound unless SiteSetting.enable_mentions?
    group = find_group(:group_id)
    @posts = group.mentioned_posts_for(
      guardian,
      params.permit(:before_post_id, :category_id)
    ).limit(50)
    @title = "#{SiteSetting.title} - #{I18n.t("rss_description.group_mentions", group_name: group.name)}"
    @link = Discourse.base_url
    @description = I18n.t("rss_description.group_mentions", group_name: group.name)
    render 'posts/latest', formats: [:rss]
  end

  def members
    group = find_group(:group_id)

    limit = (params[:limit] || 20).to_i
    offset = params[:offset].to_i

    if limit < 0
      raise Discourse::InvalidParameters.new(:limit)
    end

    if offset < 0
      raise Discourse::InvalidParameters.new(:offset)
    end

    dir = (params[:desc] && !params[:desc].blank?) ? 'DESC' : 'ASC'
    order = ""

    if params[:order] && %w{last_posted_at last_seen_at}.include?(params[:order])
      order = "#{params[:order]} #{dir} NULLS LAST"
    end

    users = group.users.human_users
    total = users.count

    if (filter = params[:filter]).present?
      filter = filter.split(',') if filter.include?(',')

      if current_user&.admin
        users = users.filter_by_username_or_email(filter)
      else
        users = users.filter_by_username(filter)
      end
    end

    members = users
      .order('NOT group_users.owner')
      .order(order)
      .order(username_lower: dir)
      .limit(limit)
      .offset(offset)

    owners = users
      .order(order)
      .order(username_lower: dir)
      .where('group_users.owner')

    render json: {
      members: serialize_data(members, GroupUserSerializer),
      owners: serialize_data(owners, GroupUserSerializer),
      meta: {
        total: total,
        limit: limit,
        offset: offset
      }
    }
  end

  def add_members
    group = Group.find(params[:id])
    group.public_admission ? ensure_logged_in : guardian.ensure_can_edit!(group)

    if params[:usernames].present?
      users = User.where(username: params[:usernames].split(","))
      raise Discourse::InvalidParameters.new(:usernames) if users.blank?
    elsif params[:user_ids].present?
      users = User.where(id: params[:user_ids].split(","))
      raise Discourse::InvalidParameters.new(:user_ids) if users.blank?
    elsif params[:user_emails].present?
      users = User.with_email(params[:user_emails].split(","))
      raise Discourse::InvalidParameters.new(:user_emails) if users.blank?
    else
      raise Discourse::InvalidParameters.new(
        'user_ids or usernames or user_emails must be present'
      )
    end

    if group.public_admission
      if !guardian.can_log_group_changes?(group) && current_user != users.first
        raise Discourse::InvalidAccess
      end

      unless current_user.staff?
        RateLimiter.new(current_user, "public_group_membership", 3, 1.minute).performed!
      end
    end

    if (usernames = group.users.where(id: users.pluck(:id)).pluck(:username)).present?
      render_json_error(I18n.t(
        "groups.errors.member_already_exist",
        username: usernames.sort.join(", "),
        count: usernames.size
      ))
    else
      users.each do |user|
        group.add(user)
        GroupActionLogger.new(current_user, group).log_add_user_to_group(user)
      end

      render json: success_json.merge!(
        usernames: users.map(&:username)
      )
    end
  end

  def mentionable
    group = find_group(:group_id, ensure_can_see: false)

    if group
      render json: { mentionable: Group.mentionable(current_user).where(id: group.id).present? }
    else
      raise Discourse::InvalidAccess.new
    end
  end

  def messageable
    group = find_group(:group_id, ensure_can_see: false)

    if group
      render json: { messageable: Group.messageable(current_user).where(id: group.id).present? }
    else
      raise Discourse::InvalidAccess.new
    end
  end

  def remove_member
    group = Group.find_by(id: params[:id])
    raise Discourse::NotFound unless group
    group.public_exit ? ensure_logged_in : guardian.ensure_can_edit!(group)

    user =
      if params[:user_id].present?
        User.find_by(id: params[:user_id])
      elsif params[:username].present?
        User.find_by_username(params[:username])
      elsif params[:user_email].present?
        User.find_by_email(params[:user_email])
      else
        raise Discourse::InvalidParameters.new('user_id or username must be present')
      end

    raise Discourse::NotFound unless user

    if group.public_exit
      if !guardian.can_log_group_changes?(group) && current_user != user
        raise Discourse::InvalidAccess
      end

      unless current_user.staff?
        RateLimiter.new(current_user, "public_group_membership", 3, 1.minute).performed!
      end
    end

    user.primary_group_id = nil if user.primary_group_id == group.id

    group.remove(user)
    GroupActionLogger.new(current_user, group).log_remove_user_from_group(user)

    if group.save && user.save
      render json: success_json
    else
      render_json_error(group)
    end
  end

  def request_membership
    params.require(:reason)

    unless current_user.staff?
      RateLimiter.new(current_user, "request_group_membership", 1, 1.day).performed!
    end

    group = find_group(:id)
    group_name = group.name

    usernames = [current_user.username].concat(
      group.users.where('group_users.owner')
        .order("users.last_seen_at DESC")
        .limit(5)
        .pluck("users.username")
    )

    post = PostCreator.new(current_user,
      title: I18n.t('groups.request_membership_pm.title', group_name: group_name),
      raw: params[:reason],
      archetype: Archetype.private_message,
      target_usernames: usernames.join(','),
      skip_validations: true
    ).create!

    render json: success_json.merge(relative_url: post.topic.relative_url)
  end

  def set_notifications
    group = find_group(:id)
    notification_level = params.require(:notification_level)

    user_id = current_user.id
    if guardian.is_staff?
      user_id = params[:user_id] || user_id
    end

    GroupUser.where(group_id: group.id)
      .where(user_id: user_id)
      .update_all(notification_level: notification_level)

    render json: success_json
  end

  def histories
    group = find_group(:group_id)
    guardian.ensure_can_edit!(group) unless current_user.admin

    page_size = 25
    offset = (params[:offset] && params[:offset].to_i) || 0

    group_histories = GroupHistory.with_filters(group, params[:filters])
      .limit(page_size)
      .offset(offset * page_size)

    render_json_dump(
      logs: serialize_data(group_histories, BasicGroupHistorySerializer),
      all_loaded: group_histories.count < page_size
    )
  end

  def search
    groups = Group.visible_groups(current_user)
      .where("groups.id <> ?", Group::AUTO_GROUPS[:everyone])
      .order(:name)

    if term = params[:term].to_s
      groups = groups.where("name ILIKE :term OR full_name ILIKE :term", term: "%#{term}%")
    end

    if params[:ignore_automatic].to_s == "true"
      groups = groups.where(automatic: false)
    end

    if Group.preloaded_custom_field_names.present?
      Group.preload_custom_fields(groups, Group.preloaded_custom_field_names)
    end

    render_serialized(groups, BasicGroupSerializer)
  end

  private

  def group_params(automatic: false)
    permitted_params =
      if automatic
        %i{
          visibility_level
          mentionable_level
          messageable_level
          default_notification_level
        }
      else
        default_params = %i{
          mentionable_level
          messageable_level
          title
          flair_url
          flair_bg_color
          flair_color
          bio_raw
          public_admission
          public_exit
          allow_membership_requests
          full_name
          default_notification_level
          membership_request_template
        }

        if current_user.admin
          default_params.push(*[
            :incoming_email,
            :primary_group,
            :visibility_level,
            :name,
            :grant_trust_level,
            :automatic_membership_email_domains,
            :automatic_membership_retroactive
          ])
        end

        default_params
      end

    params.require(:group).permit(*permitted_params)
  end

  def find_group(param_name, ensure_can_see: true)
    name = params.require(param_name)
    group = Group
    group = group.find_by("lower(name) = ?", name.downcase)
    guardian.ensure_can_see!(group) if ensure_can_see
    group
  end
end
