class EmbedController < ApplicationController
  skip_before_action :check_xhr, :preload_json, :verify_authenticity_token

  before_action :ensure_embeddable, except: [ :info ]
  before_action :get_embeddable_css_class, except: [ :info ]
  before_action :ensure_api_request, only: [ :info ]

  layout 'embed'

  rescue_from Discourse::InvalidAccess do
    response.headers['X-Frame-Options'] = "ALLOWALL"
    if current_user.try(:admin?)
      @setup_url = "#{Discourse.base_url}/admin/customize/embedding"
      @show_reason = true
      @hosts = EmbeddableHost.all
    end
    render 'embed_error'
  end

  def comments
    embed_url = params[:embed_url]
    embed_username = params[:discourse_username]

    topic_id = nil
    if embed_url.present?
      topic_id = TopicEmbed.topic_id_for_embed(embed_url)
    else
      topic_id = params[:topic_id].to_i
    end

    if topic_id
      @topic_view = TopicView.new(topic_id,
                                  current_user,
                                  limit: SiteSetting.embed_post_limit,
                                  exclude_first: true,
                                  exclude_deleted_users: true,
                                  exclude_hidden: true)

      @second_post_url = "#{@topic_view.topic.url}/2" if @topic_view
      @posts_left = 0
      if @topic_view && @topic_view.posts.size == SiteSetting.embed_post_limit
        @posts_left = @topic_view.topic.posts_count - SiteSetting.embed_post_limit - 1
      end

      if @topic_view
        @reply_count = @topic_view.topic.posts_count - 1
        @reply_count = 0 if @reply_count < 0
      end
    elsif embed_url.present?
      Jobs.enqueue(:retrieve_topic,
                      user_id: current_user.try(:id),
                      embed_url: embed_url,
                      author_username: embed_username,
                      referer: request.env['HTTP_REFERER']
                  )
      render 'loading'
    end

    discourse_expires_in 1.minute
  end

  def info
    embed_url = params.require(:embed_url)
    @topic_embed = TopicEmbed.where(embed_url: embed_url).first

    raise Discourse::NotFound if @topic_embed.nil?

    render_serialized(@topic_embed, TopicEmbedSerializer, root: false)
  end

  def count
    embed_urls = params[:embed_url]
    by_url = {}

    if embed_urls.present?
      urls = embed_urls.map { |u| u.sub(/#discourse-comments$/, '').sub(/\/$/, '') }
      topic_embeds = TopicEmbed.where(embed_url: urls).includes(:topic).references(:topic)

      topic_embeds.each do |te|
        url = te.embed_url
        url = "#{url}#discourse-comments" unless params[:embed_url].include?(url)
        if te.topic.present?
          by_url[url] = I18n.t('embed.replies', count: te.topic.posts_count - 1)
        else
          by_url[url] = I18n.t('embed.replies', count: 0)
        end
      end
    end

    render json: { counts: by_url }, callback: params[:callback]
  end

  private

  def get_embeddable_css_class
    @embeddable_css_class = ""
    embeddable_host = EmbeddableHost.record_for_url(request.referer)
    @embeddable_css_class = " class=\"#{embeddable_host.class_name}\"" if embeddable_host.present? && embeddable_host.class_name.present?
  end

  def ensure_api_request
    raise Discourse::InvalidAccess.new('api key not set') if !is_api?
  end

  def ensure_embeddable
    if !(Rails.env.development? && current_user&.admin?)
      referer = request.referer

      unless referer && EmbeddableHost.url_allowed?(referer)
        raise Discourse::InvalidAccess.new('invalid referer host')
      end
    end

    response.headers['X-Frame-Options'] = "ALLOWALL"
  rescue URI::InvalidURIError
    raise Discourse::InvalidAccess.new('invalid referer host')
  end

end
