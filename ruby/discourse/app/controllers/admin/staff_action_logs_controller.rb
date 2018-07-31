class Admin::StaffActionLogsController < Admin::AdminController

  def index
    filters = params.slice(*UserHistory.staff_filters)

    staff_action_logs = UserHistory.staff_action_records(current_user, filters).to_a
    render json: StaffActionLogsSerializer.new({
      staff_action_logs: staff_action_logs,
      user_history_actions: UserHistory.staff_actions.sort.map { |name| { id: UserHistory.actions[name], name: name } }
    }, root: false)
  end

  def diff
    require_dependency "discourse_diff"

    @history = UserHistory.find(params[:id])
    prev = @history.previous_value
    cur = @history.new_value

    prev = JSON.parse(prev) if prev
    cur = JSON.parse(cur) if cur

    diff_fields = {}

    output = "<h2>#{CGI.escapeHTML(cur["name"].to_s)}</h2><p></p>"

    diff_fields["name"] = {
      prev: prev["name"].to_s,
      cur: cur["name"].to_s,
    }

    ["default", "user_selectable"].each do |f|
      diff_fields[f] = {
        prev: (!!prev[f]).to_s,
        cur: (!!cur[f]).to_s
      }
    end

    diff_fields["color scheme"] = {
      prev: prev["color_scheme"]&.fetch("name").to_s,
      cur: cur["color_scheme"]&.fetch("name").to_s,
    }

    diff_fields["included themes"] = {
      prev: child_themes(prev),
      cur: child_themes(cur)
    }

    load_diff(diff_fields, :cur, cur)
    load_diff(diff_fields, :prev, prev)

    diff_fields.delete_if { |k, v| v[:cur] == v[:prev] }

    diff_fields.each do |k, v|
      output << "<h3>#{k}</h3><p></p>"
      diff = DiscourseDiff.new(v[:prev] || "", v[:cur] || "")
      output << diff.side_by_side_markdown
    end

    render json: { side_by_side: output }
  end

  protected

  def child_themes(theme)
    return "" unless children = theme["child_themes"]

    children.map { |row| row["name"] }.join(" ").to_s
  end

  def load_diff(hash, key, val)
    if f = val["theme_fields"]
      f.each do |row|
        entry = hash[row["target"] + " " + row["name"]] ||= {}
        entry[key] = row["value"]
      end
    end
  end

end
