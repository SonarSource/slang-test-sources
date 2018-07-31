class ThemeSettingsSerializer < ApplicationSerializer
  attributes :setting, :type, :default, :value, :description, :valid_values

  def setting
    object.name
  end

  def type
    object.type_name
  end

  def default
    object.default
  end

  def value
    object.value
  end

  def description
    object.description
  end

  def valid_values
    object.choices
  end

  def include_valid_values?
    object.type == ThemeSetting.types[:enum]
  end

  def include_description?
    object.description.present?
  end
end
