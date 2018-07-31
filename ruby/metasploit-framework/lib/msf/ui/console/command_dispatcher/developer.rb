# -*- coding: binary -*-

class Msf::Ui::Console::CommandDispatcher::Developer

  include Msf::Ui::Console::CommandDispatcher

  def initialize(driver)
    super
  end

  def name
    'Developer'
  end

  def commands
    {
      'edit'       => 'Edit the current module or a file with the preferred editor',
      'reload_lib' => 'Reload one or more library files from specified paths',
      'log'        => 'Displays framework.log starting at the bottom if possible'
    }
  end

  def local_editor
    framework.datastore['LocalEditor'] || Rex::Compat.getenv('VISUAL') || Rex::Compat.getenv('EDITOR')
  end

  def local_pager
    framework.datastore['LocalPager'] || Rex::Compat.getenv('PAGER') || Rex::Compat.getenv('MANPAGER')
  end

  # XXX: This will try to reload *any* .rb and break on modules
  def reload_file(path)
    unless File.exist?(path) && path.end_with?('.rb')
      print_error("#{path} must exist and be a .rb file")
      return
    end

    # The file must exist to reach this, so we try our best here
    if path =~ %r{^(?:\./)?modules/}
      print_error('Reloading Metasploit modules is not supported (try "reload")')
      return
    end

    print_status("Reloading #{path}")
    load path
  end

  def cmd_edit_help
    print_line 'Usage: edit [file/to/edit]'
    print_line
    print_line "Edit the currently active module or a local file with #{local_editor}."
    print_line 'If a library file is specified, it will automatically be reloaded after editing.'
    print_line "Otherwise, you can reload the active module with 'reload' or 'rerun'."
    print_line
  end

  #
  # Edit the current module or a file with the preferred editor
  #
  def cmd_edit(*args)
    editing_module = false

    if args.length > 0
      path = File.expand_path(args[0])
    elsif active_module
      editing_module = true
      path = active_module.file_path
    end

    unless path
      print_error('Nothing to edit. Try using a module first or specifying a library file to edit.')
      return
    end

    editor = local_editor

    unless editor
      editor = 'vim'
      print_warning("LocalEditor or $VISUAL/$EDITOR should be set. Falling back on #{editor}.")
    end

    # XXX: No vprint_status in this context?
    # XXX: VERBOSE is a string instead of Bool??
    print_status("Launching #{editor} #{path}") if framework.datastore['VERBOSE'].to_s == 'true'

    unless system(*editor.split, path)
      print_error("Could not execute #{editor} #{path}")
      return
    end

    return if editing_module

    reload_file(path)
  end

  #
  # Tab completion for the edit command
  #
  def cmd_edit_tabs(str, words)
    tab_complete_filenames(str, words)
  end

  def cmd_reload_lib_help
    print_line 'Usage: reload_lib lib/to/reload.rb [...]'
    print_line
    print_line 'Reload one or more library files from specified paths.'
    print_line
  end

  #
  # Reload one or more library files from specified paths
  #
  def cmd_reload_lib(*args)
    if args.empty? || args.include?('-h') || args.include?('--help')
      cmd_reload_lib_help
      return
    end

    args.each { |path| reload_file(path) }
  end

  #
  # Tab completion for the reload_lib command
  #
  def cmd_reload_lib_tabs(str, words)
    tab_complete_filenames(str, words)
  end

  def cmd_log_help
    print_line 'Usage: log'
    print_line
    print_line 'Displays framework.log starting at the bottom if possible.'
    print_line "For full effect, 'setg LogLevel 3' before running modules."
    print_line
    print_line "Log location: #{File.join(Msf::Config.log_directory, 'framework.log')}"
    print_line
  end

  #
  # Displays framework.log starting at the bottom if possible
  #
  def cmd_log(*args)
    path = File.join(Msf::Config.log_directory, 'framework.log')

    # XXX: +G isn't portable and may hang on large files
    pager = local_pager.to_s.include?('less') ? "#{local_pager} +G" : local_pager

    unless pager
      pager = 'tail -n 24'
      print_warning("LocalPager or $PAGER/$MANPAGER should be set. Falling back on #{pager}.")
    end

    # XXX: No vprint_status in this context?
    # XXX: VERBOSE is a string instead of Bool??
    print_status("Launching #{pager} #{path}") if framework.datastore['VERBOSE'].to_s == 'true'

    unless system(*pager.split, path)
      print_error("Could not execute #{pager} #{path}")
    end
  end

end
