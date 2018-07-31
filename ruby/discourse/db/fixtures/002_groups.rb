Group.ensure_automatic_groups!
if g = Group.find_by(name: 'trust_level_5', id: 15)
  g.destroy!
end

Group.where(name: 'everyone').update_all(visibility_level: Group.visibility_levels[:owners])
