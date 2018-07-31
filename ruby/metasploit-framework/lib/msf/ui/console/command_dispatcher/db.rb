# -*- coding: binary -*-

require 'json'
require 'rexml/document'
require 'rex/parser/nmap_xml'
require 'msf/core/db_export'
require 'metasploit/framework/data_service'
require 'metasploit/framework/data_service/remote/http/core'

module Msf
module Ui
module Console
module CommandDispatcher

class Db

  require 'tempfile'

  include Msf::Ui::Console::CommandDispatcher
  include Msf::Ui::Console::CommandDispatcher::Common

  #
  # The dispatcher's name.
  #
  def name
    "Database Backend"
  end

  #
  # Returns the hash of commands supported by this dispatcher.
  #
  def commands
    base = {
      "db_connect"    => "Connect to an existing database",
      "db_disconnect" => "Disconnect from the current database instance",
      "db_status"     => "Show the current database status",
    }

    more = {
      "workspace"     => "Switch between database workspaces",
      "hosts"         => "List all hosts in the database",
      "services"      => "List all services in the database",
      "vulns"         => "List all vulnerabilities in the database",
      "notes"         => "List all notes in the database",
      "loot"          => "List all loot in the database",
      "db_import"     => "Import a scan result file (filetype will be auto-detected)",
      "db_export"     => "Export a file containing the contents of the database",
      "db_nmap"       => "Executes nmap and records the output automatically",
      "db_rebuild_cache" => "Rebuilds the database-stored module cache",
      "data_services" => "Command to add, list and set a data service",
    }

    # Always include commands that only make sense when connected.
    # This avoids the problem of them disappearing unexpectedly if the
    # database dies or times out.  See #1923
    base.merge(more)
  end

  def deprecated_commands
    [
      "db_autopwn",
      "db_driver",
      "db_hosts",
      "db_notes",
      "db_services",
      "db_vulns",
    ]
  end

  #
  # Returns true if the db is connected, prints an error and returns
  # false if not.
  #
  # All commands that require an active database should call this before
  # doing anything.
  #
  def active?
    if not framework.db.active
      print_error("Database not connected")
      return false
    end
    true
  end

  def cmd_data_services(*args)
    while (arg = args.shift)
      case arg
        when '-h', '--help'
          data_service_help
          return
        when '-a', '--add'
          add_data_service(*args)
          return
        when '-d', '--delete'
          delete_data_service(args.shift)
          return
        when '-s', '--set'
          set_data_service(args.shift)
          return
      end
    end

    list_data_services
  end


  def cmd_workspace_help
    print_line "Usage:"
    print_line "    workspace                  List workspaces"
    print_line "    workspace -v               List workspaces verbosely"
    print_line "    workspace [name]           Switch workspace"
    print_line "    workspace -a [name] ...    Add workspace(s)"
    print_line "    workspace -d [name] ...    Delete workspace(s)"
    print_line "    workspace -D               Delete all workspaces"
    print_line "    workspace -r <old> <new>   Rename workspace"
    print_line "    workspace -h               Show this help information"
    print_line
  end

  def cmd_workspace(*args)
    return unless active?
    search_term = nil
    while (arg = args.shift)
      case arg
      when '-h','--help'
        cmd_workspace_help
        return
      when '-a','--add'
        adding = true
      when '-d','--del'
        deleting = true
      when '-D','--delete-all'
        delete_all = true
      when '-r','--rename'
        renaming = true
      when '-v','--verbose'
        verbose = true
      when '-S', '--search'
        search_term = args.shift
      else
        names ||= []
        names << arg
      end
    end

    if adding and names
      # Add workspaces
      wspace = nil
      names.each do |name|
        wspace = framework.db.workspaces(name: name).first
        if wspace
          print_status("Workspace '#{wspace.name}' already existed, switching to it.")
        else
          wspace = framework.db.add_workspace(name)
          print_status("Added workspace: #{wspace.name}")
        end
      end
      framework.db.workspace = wspace
      print_status("Workspace: #{framework.db.workspace.name}")
    elsif deleting and names
      ws_ids_to_delete = []
      starting_ws = framework.db.workspace
      names.uniq.each do |n|
        ws = framework.db.workspaces(name: n).first
        ws_ids_to_delete << ws.id if ws
      end
      if ws_ids_to_delete.count > 0
        deleted = framework.db.delete_workspaces(ids: ws_ids_to_delete)
        process_deleted_workspaces(deleted, starting_ws)
      else
        print_status("No workspaces matching the given name(s) were found.")
      end
    elsif delete_all
      ws_ids_to_delete = []
      starting_ws = framework.db.workspace
      framework.db.workspaces.each do |ws|
        ws_ids_to_delete << ws.id
      end
      deleted = framework.db.delete_workspaces(ids: ws_ids_to_delete)
      process_deleted_workspaces(deleted, starting_ws)
    elsif renaming
      if names.length != 2
        print_error("Wrong number of arguments to rename")
        return
      end

      ws_to_update = framework.db.find_workspace(names.first)
      unless ws_to_update
        print_error("Workspace '#{names.first}' does not exist")
        return
      end
      opts = {
          id: ws_to_update.id,
          name: names.last
      }
      begin
        if names.last == Msf::DBManager::Workspace::DEFAULT_WORKSPACE_NAME
          print_error("Unable to rename a workspace to '#{Msf::DBManager::Workspace::DEFAULT_WORKSPACE_NAME}'")
          return
        end
        updated_ws = framework.db.update_workspace(opts)
        if updated_ws
          framework.db.workspace = updated_ws if names.first == framework.db.workspace.name
          print_status("Renamed workspace '#{names.first}' to '#{updated_ws.name}'")
        else
          print_error "There was a problem updating the workspace. Setting to the default workspace."
          framework.db.workspace = framework.db.default_workspace
          return
        end
        if names.first == Msf::DBManager::Workspace::DEFAULT_WORKSPACE_NAME
          print_status("Recreated default workspace")
        end
      rescue => e
        print_error "Failed to rename workspace: #{e.message}"
        e.backtrace.each { |line| print_error "#{line}"}
      end

    elsif names
      name = names.last
      # Switch workspace
      workspace = framework.db.find_workspace(name)
      if workspace
        framework.db.workspace = workspace
        print_status("Workspace: #{workspace.name}")
      else
        print_error("Workspace not found: #{name}")
        return
      end
    else
      current_workspace = framework.db.workspace

      unless verbose
        current = nil
        framework.db.workspaces.sort_by {|s| s.name}.each do |s|
          if s.name == current_workspace.name
            current = s.name
          else
            print_line("  #{s.name}")
          end
        end
        print_line("%red* #{current}%clr") unless current.nil?
        return
      end
      col_names = %w{current name hosts services vulns creds loots notes}

      tbl = Rex::Text::Table.new(
        'Header'     => 'Workspaces',
        'Columns'    => col_names,
        'SortIndex'  => -1,
        'SearchTerm' => search_term
      )

      framework.db.workspaces.each do |ws|
        tbl << [
          current_workspace.name == ws.name ? '*' : '',
          ws.name,
          framework.db.hosts(workspace: ws.name).count,
          framework.db.services(workspace: ws.name).count,
          framework.db.vulns(workspace: ws.name).count,
          framework.db.creds(workspace: ws.name).count,
          framework.db.loots(workspace: ws.name).count,
          framework.db.notes(workspace: ws.name).count
        ]
      end

      print_line
      print_line(tbl.to_s)
    end
  end

  def process_deleted_workspaces(deleted_workspaces, starting_ws)
    deleted_workspaces.each do |ws|
      print_status "Deleted workspace: #{ws.name}"
      if ws.name == Msf::DBManager::Workspace::DEFAULT_WORKSPACE_NAME
        framework.db.workspace = framework.db.default_workspace
        print_status 'Recreated the default workspace'
      elsif ws == starting_ws
        framework.db.workspace = framework.db.default_workspace
        print_status "Switched to workspace: #{framework.db.workspace.name}"
      end
    end
  end

  def cmd_workspace_tabs(str, words)
    return [] unless active?
    framework.db.workspaces.map { |s| s.name } if (words & ['-a','--add']).empty?
  end

  def cmd_hosts_help
    # This command does some lookups for the list of appropriate column
    # names, so instead of putting all the usage stuff here like other
    # help methods, just use it's "-h" so we don't have to recreating
    # that list
    cmd_hosts("-h")
  end

  # Changes the specified host data
  #
  # @param host_ranges - range of hosts to process
  # @param host_data - hash of host data to be updated
  def change_host_data(host_ranges, host_data)
    if !host_data || host_data.length != 1
      print_error("A single key-value data hash is required to change the host data")
      return
    end
    attribute = host_data.keys[0]

    if host_ranges == [nil]
      print_error("In order to change the host #{attribute}, you must provide a range of hosts")
      return
    end

    each_host_range_chunk(host_ranges) do |host_search|
      break if !host_search.nil? && host_search.empty?

      framework.db.hosts(address: host_search).each do |host|
        framework.db.update_host(host_data.merge(id: host.id))
        framework.db.report_note(host: host.address, type: "host.#{attribute}", data: host_data[attribute])
      end
    end
  end

  def add_host_tag(rws, tag_name)
    if rws == [nil]
      print_error("In order to add a tag, you must provide a range of hosts")
      return
    end

    opts = Hash.new()
    opts[:workspace] = framework.db.workspace
    opts[:tag_name] = tag_name

    rws.each do |rw|
      rw.each do |ip|
        opts[:ip] = ip
        framework.db.add_host_tag(opts)
      end
    end
  end

  def find_hosts_with_tag(workspace_id, host_address, tag_name)
    opts = Hash.new()
    opts[:workspace_id] = workspace_id
    opts[:host_address] = host_address
    opts[:tag_name] = tag_name

    framework.db.find_hosts_with_tag(opts)
  end

  def find_host_tags(workspace_id, host_address)
    opts = Hash.new()
    opts[:workspace_id] = workspace_id
    opts[:host_address] = host_address

    framework.db.find_host_tags(opts)
  end

  def delete_host_tag(rws, tag_name)
    opts = Hash.new()
    opts[:workspace] = framework.db.workspace
    opts[:tag_name] = tag_name

    if rws == [nil]
      framework.db.delete_host_tag(opts)
    else
      rws.each do |rw|
        rw.each do |ip|
          opts[:ip] = ip
          framework.db.delete_host_tag(opts)
        end
      end
    end

  end

  @@hosts_columns = [ 'address', 'mac', 'name', 'os_name', 'os_flavor', 'os_sp', 'purpose', 'info', 'comments']

  def cmd_hosts(*args)
    return unless active?
    onlyup = false
    set_rhosts = false
    mode = []
    delete_count = 0

    rhosts = []
    host_ranges = []
    search_term = nil

    output = nil
    default_columns = [
        'address',
        'arch',
        'comm',
        'comments',
        'created_at',
        'cred_count',
        'detected_arch',
        'exploit_attempt_count',
        'host_detail_count',
        'info',
        'mac',
        'name',
        'note_count',
        'os_family',
        'os_flavor',
        'os_lang',
        'os_name',
        'os_sp',
        'purpose',
        'scope',
        'service_count',
        'state',
        'updated_at',
        'virtual_host',
        'vuln_count',
        'workspace_id']

    default_columns << 'tags' # Special case
    virtual_columns = [ 'svcs', 'vulns', 'workspace', 'tags' ]

    col_search = @@hosts_columns

    default_columns.delete_if {|v| (v[-2,2] == "id")}
    while (arg = args.shift)
      case arg
      when '-a','--add'
        mode << :add
      when '-d','--delete'
        mode << :delete
      when '-c','-C'
        list = args.shift
        if(!list)
          print_error("Invalid column list")
          return
        end
        col_search = list.strip().split(",")
        col_search.each { |c|
          if not default_columns.include?(c) and not virtual_columns.include?(c)
            all_columns = default_columns + virtual_columns
            print_error("Invalid column list. Possible values are (#{all_columns.join("|")})")
            return
          end
        }
        if (arg == '-C')
          @@hosts_columns = col_search
        end

      when '-u','--up'
        onlyup = true
      when '-o'
        output = args.shift
      when '-O'
        if (order_by = args.shift.to_i - 1) < 0
          print_error('Please specify a column number starting from 1')
          return
        end
      when '-R', '--rhosts'
        set_rhosts = true
      when '-S', '--search'
        search_term = args.shift
      when '-i', '--info'
        mode << :new_info
        info_data = args.shift
      when '-n', '--name'
        mode << :new_name
        name_data = args.shift
      when '-m', '--comment'
        mode << :new_comment
        comment_data = args.shift
      when '-t', '--tag'
        mode << :tag
        tag_name = args.shift
      when '-h','--help'
        print_line "Usage: hosts [ options ] [addr1 addr2 ...]"
        print_line
        print_line "OPTIONS:"
        print_line "  -a,--add          Add the hosts instead of searching"
        print_line "  -d,--delete       Delete the hosts instead of searching"
        print_line "  -c <col1,col2>    Only show the given columns (see list below)"
        print_line "  -C <col1,col2>    Only show the given columns until the next restart (see list below)"
        print_line "  -h,--help         Show this help information"
        print_line "  -u,--up           Only show hosts which are up"
        print_line "  -o <file>         Send output to a file in csv format"
        print_line "  -O <column>       Order rows by specified column number"
        print_line "  -R,--rhosts       Set RHOSTS from the results of the search"
        print_line "  -S,--search       Search string to filter by"
        print_line "  -i,--info         Change the info of a host"
        print_line "  -n,--name         Change the name of a host"
        print_line "  -m,--comment      Change the comment of a host"
        print_line "  -t,--tag          Add or specify a tag to a range of hosts"
        print_line
        print_line "Available columns: #{default_columns.join(", ")}"
        print_line
        return
      else
        # Anything that wasn't an option is a host to search for
        unless (arg_host_range(arg, host_ranges))
          return
        end
      end
    end

    if col_search
      col_names = col_search
    else
      col_names = default_columns + virtual_columns
    end

    mode << :search if mode.empty?

    if mode == [:add]
      host_ranges.each do |range|
        range.each do |address|
          host = framework.db.find_or_create_host(:host => address)
          print_status("Time: #{host.created_at} Host: host=#{host.address}")
        end
      end
      return
    end

    cp_hsh = {}
    col_names.map do |col|
      cp_hsh[col] = { 'MaxChar' => 52 }
    end
    # If we got here, we're searching.  Delete implies search
    tbl = Rex::Text::Table.new(
      {
        'Header'  => "Hosts",
        'Columns' => col_names,
        'ColProps' => cp_hsh,
        'SortIndex' => order_by
      })

    # Sentinel value meaning all
    host_ranges.push(nil) if host_ranges.empty?

    case
    when mode == [:new_info]
        change_host_data(host_ranges, info: info_data)
      return
    when mode == [:new_name]
        change_host_data(host_ranges, name: name_data)
      return
    when mode == [:new_comment]
        change_host_data(host_ranges, comments: comment_data)
      return
    when mode == [:tag]
      begin
        add_host_tag(host_ranges, tag_name)
      rescue => e
        if e.message.include?('Validation failed')
          print_error(e.message)
        else
          raise e
        end
      end
      return
    when mode.include?(:tag) && mode.include?(:delete)
      delete_host_tag(host_ranges, tag_name)
      return
    end

    matched_host_ids = []
    each_host_range_chunk(host_ranges) do |host_search|
      break if !host_search.nil? && host_search.empty?

      framework.db.hosts(address: host_search, non_dead: onlyup, search_term: search_term).each do |host|
        matched_host_ids << host.id
        columns = col_names.map do |n|
          # Deal with the special cases
          if virtual_columns.include?(n)
            case n
            when "svcs";      host.service_count
            when "vulns";     host.vuln_count
            when "workspace"; host.workspace.name
            when "tags"
              found_tags = find_host_tags(framework.db.workspace.id, host.address)
              tag_names = []
              found_tags.each { |t| tag_names << t.name }
              found_tags * ", "
            end
          # Otherwise, it's just an attribute
          else
            host[n] || ""
          end
        end

        tbl << columns
        if set_rhosts
          addr = (host.scope ? host.address + '%' + host.scope : host.address)
          rhosts << addr
        end
      end

      if mode == [:delete]
        result = framework.db.delete_host(ids: matched_host_ids)
        delete_count += result.size
      end
    end

    if output
      print_status("Wrote hosts to #{output}")
      ::File.open(output, "wb") { |ofd|
        ofd.write(tbl.to_csv)
      }
    else
      print_line
      print_line(tbl.to_s)
    end

    # Finally, handle the case where the user wants the resulting list
    # of hosts to go into RHOSTS.
    set_rhosts_from_addrs(rhosts.uniq) if set_rhosts

    print_status("Deleted #{delete_count} hosts") if delete_count > 0
  end

  def cmd_services_help
    # Like cmd_hosts, use "-h" instead of recreating the column list
    # here
    cmd_services("-h")
  end

  def cmd_services(*args)
    return unless active?
    mode = :search
    onlyup = false
    output_file = nil
    set_rhosts = false
    col_search = ['port', 'proto', 'name', 'state', 'info']
    default_columns = [
        'created_at',
        'info',
        'name',
        'port',
        'proto',
        'state',
        'updated_at']

    host_ranges  = []
    port_ranges  = []
    rhosts       = []
    delete_count = 0
    search_term  = nil
    opts         = {}

    # option parsing
    while (arg = args.shift)
      case arg
        when '-a','--add'
          mode = :add
        when '-d','--delete'
          mode = :delete
        when '-U', '--update'
          mode = :update
        when '-u','--up'
          onlyup = true
        when '-c'
          list = args.shift
          if(!list)
            print_error("Invalid column list")
            return
          end
          col_search = list.strip().split(",")
          col_search.each { |c|
            if not default_columns.include? c
              print_error("Invalid column list. Possible values are (#{default_columns.join("|")})")
              return
            end
          }
        when '-p'
          unless (arg_port_range(args.shift, port_ranges, true))
            return
          end
        when '-r'
          proto = args.shift
          if (!proto)
            print_status("Invalid protocol")
            return
          end
          proto = proto.strip
        when '-s'
          namelist = args.shift
          if (!namelist)
            print_error("Invalid name list")
            return
          end
          names = namelist.strip().split(",")
        when '-o'
          output_file = args.shift
          if (!output_file)
            print_error("Invalid output filename")
            return
          end
          output_file = ::File.expand_path(output_file)
        when '-O'
          if (order_by = args.shift.to_i - 1) < 0
            print_error('Please specify a column number starting from 1')
            return
          end
        when '-R', '--rhosts'
          set_rhosts = true
        when '-S', '--search'
          search_term = args.shift
          opts[:search_term] = search_term
        when '-h','--help'
          print_line
          print_line "Usage: services [-h] [-u] [-a] [-r <proto>] [-p <port1,port2>] [-s <name1,name2>] [-o <filename>] [addr1 addr2 ...]"
          print_line
          print_line "  -a,--add          Add the services instead of searching"
          print_line "  -d,--delete       Delete the services instead of searching"
          print_line "  -c <col1,col2>    Only show the given columns"
          print_line "  -h,--help         Show this help information"
          print_line "  -s <name>         Name of the service to add"
          print_line "  -p <port>         Search for a list of ports"
          print_line "  -r <protocol>     Protocol type of the service being added [tcp|udp]"
          print_line "  -u,--up           Only show services which are up"
          print_line "  -o <file>         Send output to a file in csv format"
          print_line "  -O <column>       Order rows by specified column number"
          print_line "  -R,--rhosts       Set RHOSTS from the results of the search"
          print_line "  -S,--search       Search string to filter by"
          print_line "  -U,--update       Update data for existing service"
          print_line
          print_line "Available columns: #{default_columns.join(", ")}"
          print_line
          return
        else
          # Anything that wasn't an option is a host to search for
          unless (arg_host_range(arg, host_ranges))
            return
          end
      end
    end

    ports = port_ranges.flatten.uniq

    if mode == :add
      # Can only deal with one port and one service name at a time
      # right now.  Them's the breaks.
      if ports.length != 1
        print_error("Exactly one port required")
        return
      end
      if host_ranges.empty?
        print_error("Host address or range required")
        return
      end
      host_ranges.each do |range|
        range.each do |addr|
          info = {
              :host => addr,
              :port => ports.first.to_i
          }
          info[:proto] = proto.downcase if proto
          info[:name]  = names.first.downcase if names and names.first

          svc = framework.db.find_or_create_service(info)
          print_status("Time: #{svc.created_at} Service: host=#{svc.host.address} port=#{svc.port} proto=#{svc.proto} name=#{svc.name}")
        end
      end
      return
    end

    # If we got here, we're searching.  Delete implies search
    col_names = default_columns
    if col_search
      col_names = col_search
    end
    tbl = Rex::Text::Table.new({
                                   'Header'    => "Services",
                                   'Columns'   => ['host'] + col_names,
                                   'SortIndex' => order_by
                               })

    # Sentinel value meaning all
    host_ranges.push(nil) if host_ranges.empty?
    ports = nil if ports.empty?
    matched_service_ids = []

    each_host_range_chunk(host_ranges) do |host_search|
      break if !host_search.nil? && host_search.empty?
      opts[:workspace] = framework.db.workspace
      opts[:hosts] = {address: host_search} if !host_search.nil?
      opts[:port] = ports if ports
      framework.db.services(opts).each do |service|

        host = service.host
        matched_service_ids << service.id

        if mode == :update
          service.name = names.first if names
          service.proto = proto if proto
          service.port = ports.first if ports
          framework.db.update_service(service.as_json.symbolize_keys)
        end

        columns = [host.address] + col_names.map { |n| service[n].to_s || "" }
        tbl << columns
        if set_rhosts
          addr = (host.scope ? host.address + '%' + host.scope : host.address )
          rhosts << addr
        end
      end
    end

    if (mode == :delete)
      result = framework.db.delete_service(ids: matched_service_ids)
      delete_count += result.size
    end

    if (output_file == nil)
      print_line(tbl.to_s)
    else
      # create the output file
      ::File.open(output_file, "wb") { |f| f.write(tbl.to_csv) }
      print_status("Wrote services to #{output_file}")
    end

    # Finally, handle the case where the user wants the resulting list
    # of hosts to go into RHOSTS.
    set_rhosts_from_addrs(rhosts.uniq) if set_rhosts

    print_status("Deleted #{delete_count} services") if delete_count > 0

  end

  def cmd_vulns_help
    print_line "Print all vulnerabilities in the database"
    print_line
    print_line "Usage: vulns [addr range]"
    print_line
    print_line "  -h,--help             Show this help information"
    print_line "  -o <file>             Send output to a file in csv format"
    print_line "  -p,--port <portspec>  List vulns matching this port spec"
    print_line "  -s <svc names>        List vulns matching these service names"
    print_line "  -R,--rhosts           Set RHOSTS from the results of the search"
    print_line "  -S,--search           Search string to filter by"
    print_line "  -i,--info             Display vuln information"
    print_line
    print_line "Examples:"
    print_line "  vulns -p 1-65536          # only vulns with associated services"
    print_line "  vulns -p 1-65536 -s http  # identified as http on any port"
    print_line
  end

  def cmd_vulns(*args)
    return unless active?

    default_columns = ['Timestamp', 'Host', 'Name', 'References']
    host_ranges = []
    port_ranges = []
    svcs        = []
    rhosts      = []

    search_term = nil
    show_info   = false
    set_rhosts  = false
    output_file = nil
    delete_count = 0

    while (arg = args.shift)
      case arg
      # when '-a', '--add'
      #   mode = :add
      when '-d', '--delete'  # TODO: This is currently undocumented because it's not officially supported.
        mode = :delete
      when '-h', '--help'
        cmd_vulns_help
        return
      when '-o', '--output'
        output_file = args.shift
        if output_file
          output_file = File.expand_path(output_file)
        else
          print_error("Invalid output filename")
          return
        end
      when '-p', '--port'
        unless (arg_port_range(args.shift, port_ranges, true))
          return
        end
      when '-s', '--service'
        service = args.shift
        if (!service)
          print_error("Argument required for -s")
          return
        end
        svcs = service.split(/[\s]*,[\s]*/)
      when '-R', '--rhosts'
        set_rhosts = true
      when '-S', '--search'
        search_term = args.shift
      when '-i', '--info'
        show_info = true
      else
        # Anything that wasn't an option is a host to search for
        unless (arg_host_range(arg, host_ranges))
          return
        end
      end
    end

    if show_info
      default_columns << 'Information'
    end

    # add sentinel value meaning all if empty
    host_ranges.push(nil) if host_ranges.empty?
    # normalize
    ports = port_ranges.flatten.uniq
    svcs.flatten!
    tbl = Rex::Text::Table.new(
        'Header' => 'Vulnerabilities',
        'Columns' => default_columns
    )

    matched_vuln_ids = []
    vulns = []
    if host_ranges.compact.empty?
      vulns = framework.db.vulns({:search_term => search_term})
    else
      each_host_range_chunk(host_ranges) do |host_search|
        break if !host_search.nil? && host_search.empty?

        vulns.concat(framework.db.vulns({:hosts => { :address => host_search }, :search_term => search_term }))
      end
    end

    vulns.each do |vuln|
      reflist = vuln.refs.map {|r| r.name}
      if (vuln.service)
        # Skip this one if the user specified a port and it
        # doesn't match.
        next unless ports.empty? or ports.include? vuln.service.port
        # Same for service names
        next unless svcs.empty? or svcs.include?(vuln.service.name)
      else
        # This vuln has no service, so it can't match
        next unless ports.empty? and svcs.empty?
      end

      matched_vuln_ids << vuln.id

      row = []
      row << vuln.created_at
      row << vuln.host.address
      row << vuln.name
      row << reflist.join(',')
      if show_info
        row << vuln.info
      end
      tbl << row

      if set_rhosts
        addr = (vuln.host.scope ? vuln.host.address + '%' + vuln.host.scope : vuln.host.address)
        rhosts << addr
      end
    end

    if mode == :delete
      result = framework.db.delete_vuln(ids: matched_vuln_ids)
      delete_count = result.size
    end

    if output_file
      File.write(output_file, tbl.to_csv)
      print_status("Wrote vulnerability information to #{output_file}")
    else
      print_line
      print_line(tbl.to_s)
    end

    # Finally, handle the case where the user wants the resulting list
    # of hosts to go into RHOSTS.
    set_rhosts_from_addrs(rhosts.uniq) if set_rhosts

    print_status("Deleted #{delete_count} vulnerabilities") if delete_count > 0
  end

  def cmd_notes_help
    print_line "Usage: notes [-h] [-t <type1,type2>] [-n <data string>] [-a] [addr range]"
    print_line
    print_line "  -a,--add                  Add a note to the list of addresses, instead of listing"
    print_line "  -d,--delete               Delete the hosts instead of searching"
    print_line "  -n,--note <data>          Set the data for a new note (only with -a)"
    print_line "  -t,--type <type1,type2>   Search for a list of types, or set single type for add"
    print_line "  -h,--help                 Show this help information"
    print_line "  -R,--rhosts               Set RHOSTS from the results of the search"
    print_line "  -S,--search               Search string to filter by"
    print_line "  -o,--output               Save the notes to a csv file"
    print_line "  -O <column>               Order rows by specified column number"
    print_line
    print_line "Examples:"
    print_line "  notes --add -t apps -n 'winzip' 10.1.1.34 10.1.20.41"
    print_line "  notes -t smb.fingerprint 10.1.1.34 10.1.20.41"
    print_line "  notes -S 'nmap.nse.(http|rtsp)'"
    print_line
  end

  def cmd_notes(*args)
    return unless active?
  ::ActiveRecord::Base.connection_pool.with_connection {
    mode = :search
    data = nil
    types = nil
    set_rhosts = false

    host_ranges = []
    rhosts      = []
    search_term = nil
    output_file = nil
    delete_count = 0

    while (arg = args.shift)
      case arg
      when '-a', '--add'
        mode = :add
      when '-d', '--delete'
        mode = :delete
      when '-n', '--note'
        data = args.shift
        if(!data)
          print_error("Can't make a note with no data")
          return
        end
      when '-t', '--type'
        typelist = args.shift
        if(!typelist)
          print_error("Invalid type list")
          return
        end
        types = typelist.strip().split(",")
      when '-R', '--rhosts'
        set_rhosts = true
      when '-S', '--search'
        search_term = args.shift
      when '-o', '--output'
        output_file = args.shift
      when '-O'
        if (order_by = args.shift.to_i - 1) < 0
          print_error('Please specify a column number starting from 1')
          return
        end
      when '-u', '--update'  # TODO: This is currently undocumented because it's not officially supported.
        mode = :update
      when '-h', '--help'
        cmd_notes_help
        return
      else
        # Anything that wasn't an option is a host to search for
        unless (arg_host_range(arg, host_ranges))
          return
        end
      end
    end

    if mode == :add
      if host_ranges.compact.empty?
        print_error("Host address or range required")
        return
      end

      if types.nil? || types.size != 1
        print_error("Exactly one type is required")
        return
      end

      if data.nil?
        print_error("Data required")
        return
      end

      type = types.first
      host_ranges.each { |range|
        range.each { |addr|
          note = framework.db.find_or_create_note(host: addr, type: type, data: data)
          break if not note
          print_status("Time: #{note.created_at} Note: host=#{addr} type=#{note.ntype} data=#{note.data}")
        }
      }
      return
    end

    if mode == :update
      if !types.nil? && types.size != 1
        print_error("Exactly one type is required")
        return
      end

      if types.nil? && data.nil?
        print_error("Update requires data or type")
        return
      end
    end

    note_list = []
    if host_ranges.compact.empty?
      # No host specified - collect all notes
      opts = {search_term: search_term}
      opts[:ntype] = types if mode != :update && types && !types.empty?
      note_list = framework.db.notes(opts)
    else
      # Collect notes of specified hosts
      each_host_range_chunk(host_ranges) do |host_search|
        break if !host_search.nil? && host_search.empty?

        opts = {hosts: {address: host_search}, workspace: framework.db.workspace, search_term: search_term}
        opts[:ntype] = types if mode != :update && types && !types.empty?
        note_list.concat(framework.db.notes(opts))
      end
    end

    # Now display them
    table = Rex::Text::Table.new(
      'Header'  => 'Notes',
      'Indent'  => 1,
      'Columns' => ['Time', 'Host', 'Service', 'Port', 'Protocol', 'Type', 'Data'],
      'SortIndex' => order_by
    )

    matched_note_ids = []
    note_list.each do |note|
      if mode == :update
        begin
          update_opts = {id: note.id}
          unless types.nil?
            note.ntype = types.first
            update_opts[:ntype] = types.first
          end

          unless data.nil?
            note.data = data
            update_opts[:data] = data
          end

          framework.db.update_note(update_opts)
        rescue => e
          elog "There was an error updating note with ID #{note.id}: #{e.message}"
          next
        end
      end

      matched_note_ids << note.id

      row = []
      row << note.created_at

      if note.host
        host = note.host
        row << host.address
        if set_rhosts
          addr = (host.scope ? host.address + '%' + host.scope : host.address)
          rhosts << addr
        end
      else
        row << ''
      end

      if note.service
        row << note.service.name || ''
        row << note.service.port || ''
        row << note.service.proto || ''
      else
        row << '' # For the Service field
        row << '' # For the Port field
        row << '' # For the Protocol field
      end

      row << note.ntype
      row << note.data.inspect
      table << row
    end

    if mode == :delete
      result = framework.db.delete_note(ids: matched_note_ids)
      delete_count = result.size
    end

    if output_file
      save_csv_notes(output_file, table)
    else
      print_line
      print_line(table.to_s)
    end

    # Finally, handle the case where the user wants the resulting list
    # of hosts to go into RHOSTS.
    set_rhosts_from_addrs(rhosts.uniq) if set_rhosts

    print_status("Deleted #{delete_count} notes") if delete_count > 0
  }
  end

  def save_csv_notes(fpath, table)
    begin
      File.open(fpath, 'wb') do |f|
        f.write(table.to_csv)
      end
      print_status("Wrote notes to #{fpath}")
    rescue Errno::EACCES => e
      print_error("Unable to save notes. #{e.message}")
    end
  end

  def cmd_loot_help
    print_line "Usage: loot <options>"
    print_line " Info: loot [-h] [addr1 addr2 ...] [-t <type1,type2>]"
    print_line "  Add: loot -f [fname] -i [info] -a [addr1 addr2 ...] -t [type]"
    print_line "  Del: loot -d [addr1 addr2 ...]"
    print_line
    print_line "  -a,--add          Add loot to the list of addresses, instead of listing"
    print_line "  -d,--delete       Delete *all* loot matching host and type"
    print_line "  -f,--file         File with contents of the loot to add"
    print_line "  -i,--info         Info of the loot to add"
    print_line "  -t <type1,type2>  Search for a list of types"
    print_line "  -h,--help         Show this help information"
    print_line "  -S,--search       Search string to filter by"
    print_line
  end

  def cmd_loot(*args)
    return unless active?

    mode = :search
    host_ranges = []
    types = nil
    delete_count = 0
    search_term = nil
    file = nil
    name = nil
    info = nil

    while (arg = args.shift)
      case arg
        when '-a','--add'
          mode = :add
        when '-d','--delete'
          mode = :delete
        when '-f','--file'
          filename = args.shift
          if(!filename)
            print_error("Can't make loot with no filename")
            return
          end
          if (!File.exist?(filename) or !File.readable?(filename))
            print_error("Can't read file")
            return
          end
        when '-i','--info'
          info = args.shift
          if(!info)
            print_error("Can't make loot with no info")
            return
          end
        when '-t', '--type'
          typelist = args.shift
          if(!typelist)
            print_error("Invalid type list")
            return
          end
          types = typelist.strip().split(",")
        when '-S', '--search'
          search_term = args.shift
        when '-u', '--update' # TODO: This is currently undocumented because it's not officially supported.
          mode = :update
        when '-h','--help'
          cmd_loot_help
          return
        else
          # Anything that wasn't an option is a host to search for
          unless (arg_host_range(arg, host_ranges))
            return
          end
      end
    end

    tbl = Rex::Text::Table.new({
        'Header'  => "Loot",
        'Columns' => [ 'host', 'service', 'type', 'name', 'content', 'info', 'path' ],
      })

    # Sentinel value meaning all
    host_ranges.push(nil) if host_ranges.empty?

    if mode == :add
      if host_ranges.compact.empty?
        print_error('Address list required')
        return
      end
      if info.nil?
        print_error("Info required")
        return
      end
      if filename.nil?
        print_error("Loot file required")
        return
      end
      if types.nil? or types.size != 1
        print_error("Exactly one loot type is required")
        return
      end
      type = types.first
      name = File.basename(filename)
      file = File.open(filename, "rb")
      contents = file.read
      host_ranges.each do |range|
        range.each do |host|
          lootfile = framework.db.find_or_create_loot(:type => type, :host => host, :info => info, :data => contents, :path => filename, :name => name)
          print_status("Added loot for #{host} (#{lootfile})")
        end
      end
      return
    end

    matched_loot_ids = []
    loots = []
    if host_ranges.compact.empty?
      loots = loots + framework.db.loots(workspace: framework.db.workspace, search_term: search_term)
    else
      each_host_range_chunk(host_ranges) do |host_search|
        break if !host_search.nil? && host_search.empty?

        loots = loots + framework.db.loots(workspace: framework.db.workspace, hosts: { address: host_search }, search_term: search_term)
      end
    end

    loots.each do |loot|
      row = []
      # TODO: This is just a temp implementation of update for the time being since it did not exist before.
      # It should be updated to not pass all of the attributes attached to the object, only the ones being updated.
      if mode == :update
        begin
          loot.info = info if info
          if types && types.size > 1
            print_error "May only pass 1 type when performing an update."
            next
          end
          loot.ltype = types.first if types
          framework.db.update_loot(loot.as_json.symbolize_keys)
        rescue => e
          elog "There was an error updating loot with ID #{loot.id}: #{e.message}"
          next
        end
      end
      row.push (loot.host && loot.host.address) ? loot.host.address : ""
      if (loot.service)
        svc = (loot.service.name ? loot.service.name : "#{loot.service.port}/#{loot.service.proto}")
        row.push svc
      else
        row.push ""
      end
      row.push(loot.ltype)
      row.push(loot.name || "")
      row.push(loot.content_type)
      row.push(loot.info || "")
      row.push(loot.path)

      tbl << row
      matched_loot_ids << loot.id
    end

    if (mode == :delete)
      result = framework.db.delete_loot(ids: matched_loot_ids)
      delete_count = result.size
    end

    print_line
    print_line(tbl.to_s)
    print_status("Deleted #{delete_count} loots") if delete_count > 0
  end

  # :category: Deprecated Commands
  def cmd_db_hosts_help; deprecated_help(:hosts); end
  # :category: Deprecated Commands
  def cmd_db_notes_help; deprecated_help(:notes); end
  # :category: Deprecated Commands
  def cmd_db_vulns_help; deprecated_help(:vulns); end
  # :category: Deprecated Commands
  def cmd_db_services_help; deprecated_help(:services); end
  # :category: Deprecated Commands
  def cmd_db_autopwn_help; deprecated_help; end
  # :category: Deprecated Commands
  def cmd_db_driver_help; deprecated_help; end

  # :category: Deprecated Commands
  def cmd_db_hosts(*args); deprecated_cmd(:hosts, *args); end
  # :category: Deprecated Commands
  def cmd_db_notes(*args); deprecated_cmd(:notes, *args); end
  # :category: Deprecated Commands
  def cmd_db_vulns(*args); deprecated_cmd(:vulns, *args); end
  # :category: Deprecated Commands
  def cmd_db_services(*args); deprecated_cmd(:services, *args); end
  # :category: Deprecated Commands
  def cmd_db_autopwn(*args); deprecated_cmd; end

  #
  # :category: Deprecated Commands
  #
  # This one deserves a little more explanation than standard deprecation
  # warning, so give the user a better understanding of what's going on.
  #
  def cmd_db_driver(*args)
    deprecated_cmd
    print_line
    print_line "Because Metasploit no longer supports databases other than the default"
    print_line "PostgreSQL, there is no longer a need to set the driver. Thus db_driver"
    print_line "is not useful and its functionality has been removed. Usually Metasploit"
    print_line "will already have connected to the database; check db_status to see."
    print_line
    cmd_db_status
  end

  def cmd_db_import_tabs(str, words)
    tab_complete_filenames(str, words)
  end

  def cmd_db_import_help
    print_line "Usage: db_import <filename> [file2...]"
    print_line
    print_line "Filenames can be globs like *.xml, or **/*.xml which will search recursively"
    print_line "Currently supported file types include:"
    print_line "    Acunetix"
    print_line "    Amap Log"
    print_line "    Amap Log -m"
    print_line "    Appscan"
    print_line "    Burp Session XML"
    print_line "    Burp Issue XML"
    print_line "    CI"
    print_line "    Foundstone"
    print_line "    FusionVM XML"
    print_line "    IP Address List"
    print_line "    IP360 ASPL"
    print_line "    IP360 XML v3"
    print_line "    Libpcap Packet Capture"
    print_line "    Masscan XML"
    print_line "    Metasploit PWDump Export"
    print_line "    Metasploit XML"
    print_line "    Metasploit Zip Export"
    print_line "    Microsoft Baseline Security Analyzer"
    print_line "    NeXpose Simple XML"
    print_line "    NeXpose XML Report"
    print_line "    Nessus NBE Report"
    print_line "    Nessus XML (v1)"
    print_line "    Nessus XML (v2)"
    print_line "    NetSparker XML"
    print_line "    Nikto XML"
    print_line "    Nmap XML"
    print_line "    OpenVAS Report"
    print_line "    OpenVAS XML"
    print_line "    Outpost24 XML"
    print_line "    Qualys Asset XML"
    print_line "    Qualys Scan XML"
    print_line "    Retina XML"
    print_line "    Spiceworks CSV Export"
    print_line "    Wapiti XML"
    print_line
  end

  #
  # Generic import that automatically detects the file type
  #
  def cmd_db_import(*args)
    return unless active?
  ::ActiveRecord::Base.connection_pool.with_connection {
    if args.include?("-h") || ! (args && args.length > 0)
      cmd_db_import_help
      return
    end
    args.each { |glob|
      files = ::Dir.glob(::File.expand_path(glob))
      if files.empty?
        print_error("No such file #{glob}")
        next
      end
      files.each { |filename|
        if (not ::File.readable?(filename))
          print_error("Could not read file #{filename}")
          next
        end
        begin
          warnings = 0
          framework.db.import_file(:filename => filename) do |type,data|
            case type
            when :debug
              print_error("DEBUG: #{data.inspect}")
            when :vuln
              inst = data[1] == 1 ? "instance" : "instances"
              print_status("Importing vulnerability '#{data[0]}' (#{data[1]} #{inst})")
            when :filetype
              print_status("Importing '#{data}' data")
            when :parser
              print_status("Import: Parsing with '#{data}'")
            when :address
              print_status("Importing host #{data}")
            when :service
              print_status("Importing service #{data}")
            when :msf_loot
              print_status("Importing loot #{data}")
            when :msf_task
              print_status("Importing task #{data}")
            when :msf_report
              print_status("Importing report #{data}")
            when :pcap_count
              print_status("Import: #{data} packets processed")
            when :record_count
              print_status("Import: #{data[1]} records processed")
            when :warning
              print_error
              data.split("\n").each do |line|
                print_error(line)
              end
              print_error
              warnings += 1
            end
          end
          print_status("Successfully imported #{filename}")

          print_error("Please note that there were #{warnings} warnings") if warnings > 1
          print_error("Please note that there was one warning") if warnings == 1

        rescue Msf::DBImportError
          print_error("Failed to import #{filename}: #{$!}")
          elog("Failed to import #{filename}: #{$!.class}: #{$!}")
          dlog("Call stack: #{$@.join("\n")}", LEV_3)
          next
        rescue REXML::ParseException => e
          print_error("Failed to import #{filename} due to malformed XML:")
          print_error("#{e.class}: #{e}")
          elog("Failed to import #{filename}: #{e.class}: #{e}")
          dlog("Call stack: #{$@.join("\n")}", LEV_3)
          next
        end
      }
    }
  }
  end

  def cmd_db_export_help
    # Like db_hosts and db_services, this creates a list of columns, so
    # use its -h
    cmd_db_export("-h")
  end

  #
  # Export an XML
  #
  def cmd_db_export(*args)
    return unless active?
  ::ActiveRecord::Base.connection_pool.with_connection {

    export_formats = %W{xml pwdump}
    format = 'xml'
    output = nil

    while (arg = args.shift)
      case arg
      when '-h','--help'
        print_line "Usage:"
        print_line "    db_export -f <format> [filename]"
        print_line "    Format can be one of: #{export_formats.join(", ")}"
      when '-f','--format'
        format = args.shift.to_s.downcase
      else
        output = arg
      end
    end

    if not output
      print_error("No output file was specified")
      return
    end

    if not export_formats.include?(format)
      print_error("Unsupported file format: #{format}")
      print_error("Unsupported file format: '#{format}'. Must be one of: #{export_formats.join(", ")}")
      return
    end

    print_status("Starting export of workspace #{framework.db.workspace.name} to #{output} [ #{format} ]...")
    framework.db.run_db_export(output, format)
    print_status("Finished export of workspace #{framework.db.workspace.name} to #{output} [ #{format} ]...")
  }
  end

  def find_nmap_path
    Rex::FileUtils.find_full_path("nmap") || Rex::FileUtils.find_full_path("nmap.exe")
  end

  #
  # Import Nmap data from a file
  #
  def cmd_db_nmap(*args)
    return unless active?
  ::ActiveRecord::Base.connection_pool.with_connection {
    if (args.length == 0)
      print_status("Usage: db_nmap [--save | [--help | -h]] [nmap options]")
      return
    end

    save = false
    arguments = []
    while (arg = args.shift)
      case arg
      when '--save'
        save = true
      when '--help', '-h'
        cmd_db_nmap_help
        return
      else
        arguments << arg
      end
    end

    nmap = find_nmap_path
    unless nmap
      print_error("The nmap executable could not be found")
      return
    end

    fd = Rex::Quickfile.new(['msf-db-nmap-', '.xml'], Msf::Config.local_directory)

    begin
      # When executing native Nmap in Cygwin, expand the Cygwin path to a Win32 path
      if(Rex::Compat.is_cygwin and nmap =~ /cygdrive/)
        # Custom function needed because cygpath breaks on 8.3 dirs
        tout = Rex::Compat.cygwin_to_win32(fd.path)
        arguments.push('-oX', tout)
      else
        arguments.push('-oX', fd.path)
      end

      begin
        nmap_pipe = ::Open3::popen3([nmap, 'nmap'], *arguments)
        temp_nmap_threads = []
        temp_nmap_threads << framework.threads.spawn("db_nmap-Stdout", false, nmap_pipe[1]) do |np_1|
          np_1.each_line do |nmap_out|
            next if nmap_out.strip.empty?
            print_status("Nmap: #{nmap_out.strip}")
          end
        end

        temp_nmap_threads << framework.threads.spawn("db_nmap-Stderr", false, nmap_pipe[2]) do |np_2|
          np_2.each_line do |nmap_err|
            next if nmap_err.strip.empty?
            print_status("Nmap: '#{nmap_err.strip}'")
          end
        end

        temp_nmap_threads.map {|t| t.join rescue nil}
        nmap_pipe.each {|p| p.close rescue nil}
      rescue ::IOError
      end

      framework.db.import_nmap_xml_file(:filename => fd.path)

      print_status("Saved NMAP XML results to #{fd.path}") if save
    ensure
      fd.close
      fd.unlink unless save
    end
  }
  end

  def cmd_db_nmap_help
    nmap = find_nmap_path
    unless nmap
      print_error("The nmap executable could not be found")
      return
    end

    stdout, stderr = Open3.capture3([nmap, 'nmap'], '--help')

    stdout.each_line do |out_line|
      next if out_line.strip.empty?
      print_status(out_line.strip)
    end

    stderr.each_line do |err_line|
      next if err_line.strip.empty?
      print_error(err_line.strip)
    end
  end

  def cmd_db_nmap_tabs(str, words)
    nmap = find_nmap_path
    unless nmap
      return
    end

    stdout, stderr = Open3.capture3([nmap, 'nmap'], '--help')
    tabs = []
    stdout.each_line do |out_line|
      if out_line.strip.starts_with?('-')
        tabs.push(out_line.strip.split(':').first)
      end
    end

    stderr.each_line do |err_line|
      next if err_line.strip.empty?
      print_error(err_line.strip)
    end

    return tabs
  end

  #
  # Database management
  #
  def db_check_driver
    unless framework.db.driver
      print_error("No database driver installed.")
      return false
    end
    true
  end

  #
  # Is everything working?
  #
  def cmd_db_status(*args)
    return if not db_check_driver

    if framework.db.connection_established?
      cdb = ''
      ::ActiveRecord::Base.connection_pool.with_connection do |conn|
        if conn.respond_to?(:current_database)
          cdb = conn.current_database
        end
      end
      print_status("#{framework.db.driver} connected to #{cdb}")
    else
      print_status("#{framework.db.driver} selected, no connection")
    end
  end

  def cmd_db_connect_help
    # Help is specific to each driver
    cmd_db_connect("-h")
  end

  def cmd_db_connect(*args)
    return if not db_check_driver
    if args[0] != '-h' && framework.db.connection_established?
      cdb = ''
      ::ActiveRecord::Base.connection_pool.with_connection do |conn|
        if conn.respond_to?(:current_database)
          cdb = conn.current_database
        end
      end
      print_error("#{framework.db.driver} already connected to #{cdb}")
      print_error('Run db_disconnect first if you wish to connect to a different database')
      return
    end
    if (args[0] == "-y")
      if (args[1] and not ::File.exist? ::File.expand_path(args[1]))
        print_error("File not found")
        return
      end
      file = args[1] || ::File.join(Msf::Config.get_config_root, "database.yml")
      file = ::File.expand_path(file)
      if (::File.exist? file)
        db = YAML.load(::File.read(file))['production']
        framework.db.connect(db)

        if framework.db.active and not framework.db.modules_cached
          print_status("Rebuilding the module cache in the background...")
          framework.threads.spawn("ModuleCacheRebuild", true) do
            framework.db.update_all_module_details
          end
        end

        return
      end
    end
    meth = "db_connect_#{framework.db.driver}"
    if(self.respond_to?(meth, true))
      self.send(meth, *args)
      if framework.db.active and not framework.db.modules_cached
        print_status("Rebuilding the module cache in the background...")
        framework.threads.spawn("ModuleCacheRebuild", true) do
          framework.db.update_all_module_details
        end
      end
    else
      print_error("This database driver #{framework.db.driver} is not currently supported")
    end
  end

  def cmd_db_disconnect_help
    print_line "Usage: db_disconnect"
    print_line
    print_line "Disconnect from the database."
    print_line
  end

  def cmd_db_disconnect(*args)
    return if not db_check_driver

    if(args[0] and (args[0] == "-h" || args[0] == "--help"))
      cmd_db_disconnect_help
      return
    end

    if (framework.db)
      framework.db.disconnect()
    end
  end

  def cmd_db_rebuild_cache
    unless framework.db.active
      print_error("The database is not connected")
      return
    end

    print_status("Purging and rebuilding the module cache in the background...")
    framework.threads.spawn("ModuleCacheRebuild", true) do
      framework.db.purge_all_module_details
      framework.db.update_all_module_details
    end
  end

  def cmd_db_rebuild_cache_help
    print_line "Usage: db_rebuild_cache"
    print_line
    print_line "Purge and rebuild the SQL module cache."
    print_line
  end

  def db_find_tools(tools)
    missed  = []
    tools.each do |name|
      if(! Rex::FileUtils.find_full_path(name))
        missed << name
      end
    end
    if(not missed.empty?)
      print_error("This database command requires the following tools to be installed: #{missed.join(", ")}")
      return
    end
    true
  end

  #
  # Database management: Postgres
  #

  #
  # Connect to an existing Postgres database
  #
  def db_connect_postgresql(*args)
    if(args[0] == nil or args[0] == "-h" or args[0] == "--help")
      print_status("   Usage: db_connect <user:pass>@<host:port>/<database>")
      print_status("      OR: db_connect -y [path/to/database.yml]")
      print_status("Examples:")
      print_status("       db_connect user@metasploit3")
      print_status("       db_connect user:pass@192.168.0.2/metasploit3")
      print_status("       db_connect user:pass@192.168.0.2:1500/metasploit3")
      return
    end

    info = db_parse_db_uri_postgresql(args[0])
    opts = { 'adapter' => 'postgresql' }

    opts['username'] = info[:user] if (info[:user])
    opts['password'] = info[:pass] if (info[:pass])
    opts['database'] = info[:name]
    opts['host'] = info[:host] if (info[:host])
    opts['port'] = info[:port] if (info[:port])

    opts['pass'] ||= ''

    # Do a little legwork to find the real database socket
    if(! opts['host'])
      while(true)
        done = false
        dirs = %W{ /var/run/postgresql /tmp }
        dirs.each do |dir|
          if(::File.directory?(dir))
            d = ::Dir.new(dir)
            d.entries.grep(/^\.s\.PGSQL.(\d+)$/).each do |ent|
              opts['port'] = ent.split('.')[-1].to_i
              opts['host'] = dir
              done = true
              break
            end
          end
          break if done
        end
        break
      end
    end

    # Default to loopback
    if(! opts['host'])
      opts['host'] = '127.0.0.1'
    end

    if (not framework.db.connect(opts))
      raise RuntimeError.new("Failed to connect to the database: #{framework.db.error}")
    end
  end

  def db_parse_db_uri_postgresql(path)
    res = {}
    if (path)
      auth, dest = path.split('@')
      (dest = auth and auth = nil) if not dest
      # remove optional scheme in database url
      auth = auth.sub(/^\w+:\/\//, "") if auth
      res[:user],res[:pass] = auth.split(':') if auth
      targ,name = dest.split('/')
      (name = targ and targ = nil) if not name
      res[:host],res[:port] = targ.split(':') if targ
    end
    res[:name] = name || 'metasploit3'
    res
  end

  #
  # Miscellaneous option helpers
  #


  #
  # Takes +host_ranges+, an Array of RangeWalkers, and chunks it up into
  # blocks of 1024.
  #
  def each_host_range_chunk(host_ranges, &block)
    # Chunk it up and do the query in batches. The naive implementation
    # uses so much memory for a /8 that it's basically unusable (1.6
    # billion IP addresses take a rather long time to allocate).
    # Chunking has roughly the same performance for small batches, so
    # don't worry about it too much.
    host_ranges.each do |range|
      if range.nil? or range.length.nil?
        chunk = nil
        end_of_range = true
      else
        chunk = []
        end_of_range = false
        # Set up this chunk of hosts to search for
        while chunk.length < 1024 and chunk.length < range.length
          n = range.next_ip
          if n.nil?
            end_of_range = true
            break
          end
          chunk << n
        end
      end

      # The block will do some
      yield chunk

      # Restart the loop with the same RangeWalker if we didn't get
      # to the end of it in this chunk.
      redo unless end_of_range
    end
  end

  #######
  private
  #######

  def add_data_service(*args)
    # database is required to use Mdm objects
    unless framework.db.active
      print_error("Database not connected; connect to an existing database with db_connect before using data_services")
      return
    end

    protocol = "http"
    port = 8080
    opts = {}
    https_opts = {}
    while (arg = args.shift)
      case arg
        when '-p', '--port'
          port = args.shift
        when '-t', '--token'
          opts[:api_token] = args.shift
        when '-s', '--ssl'
          protocol = "https"
        when '-c', '--cert'
          https_opts[:cert] = args.shift
        when '--skip-verify'
          https_opts[:skip_verify] = true
        else
          host = arg
      end
    end

    if host.nil? || port.nil?
      print_error("Host and port are required")
      return
    end

    opts[:https_opts] = https_opts unless https_opts.empty?
    endpoint = "#{protocol}://#{host}:#{port}"
    remote_data_service = Metasploit::Framework::DataService::RemoteHTTPDataService.new(endpoint, opts)
    begin
      framework.db.register_data_service(remote_data_service)
      print_line "Registered data service: #{remote_data_service.name}"
      framework.db.workspace = framework.db.default_workspace
    rescue => e
      print_error "There was a problem registering the remote data service: #{e.message}"
    end
  end

  def delete_data_service(service_id)
    begin
      data_service = framework.db.delete_data_service(service_id)
      framework.db.workspace = framework.db.default_workspace
      data_service
    rescue => e
      print_error "Unable to delete data service: #{e.message}"
    end
  end

  def set_data_service(service_id)
    begin
      data_service = framework.db.set_data_service(service_id)
      framework.db.workspace = framework.db.default_workspace
      data_service
    rescue => e
      print_error "Unable to set data service: #{e.message}"
    end
  end

  def list_data_services()
    framework.db.get_services_metadata.each {|metadata|
      out = "id: #{metadata.id}, name: #{metadata.name}"
      if metadata.active
        out += " [active]"
      end
      print_line out
    }
  end

  def data_service_help
    print_line "Usage: data_services [ options ] - list data services by default"
    print_line
    print_line "OPTIONS:"

    print_line "  -h, --help                    Show this help information."
    print_line "  -d, --delete <id>             Delete the data service by identifier."
    print_line "  -s, --set <id>                Set the active data service by identifier."
    print_line "  -a, --add [ options ] <host>  Add a new data service"
    print_line "  Add Data Service Options:"
    print_line "  -p, --port <port>   The port the data service is listening on. Default is 8080."
    print_line "  -t, --token <token> API Token for MSF web service"
    print_line "  -s, --ssl           Enable SSL. Required for HTTPS data services."
    print_line "  -c, --cert          Certificate file matching the server's certificate. Needed when using self-signed SSL cert."
    print_line "  --skip-verify       Skip validating authenticity of server's certificate. NOT RECOMMENDED."
    print_line
  end

  def print_msgs(status_msg, error_msg)
    status_msg.each do |s|
      print_status(s)
    end

    error_msg.each do |e|
      print_error(e)
    end
  end

end

end end end end
