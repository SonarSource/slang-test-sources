class BadgeSerializer < ApplicationSerializer
  attributes :id, :name, :description, :grant_count, :allow_title,
             :multiple_grant, :icon, :image, :listable, :enabled, :badge_grouping_id,
             :system, :long_description, :slug, :has_badge, :manually_grantable?

  has_one :badge_type

  def include_has_badge?
    object.has_badge
  end

  def has_badge
    true
  end

  def system
    object.system?
  end

  def include_long_description?
    options[:include_long_description]
  end

  def name
    object.display_name
  end
end
