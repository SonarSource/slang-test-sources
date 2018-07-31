require "log4r"
require "securerandom"
require Vagrant.source_root.join("plugins/provisioners/shell/config")

module VagrantPlugins
  module Kernel_V2
    # Represents a single configured provisioner for a VM.
    class VagrantConfigTrigger < Vagrant.plugin("2", :config)
      # Defaults
      DEFAULT_ON_ERROR = :halt
      DEFAULT_EXIT_CODE = 0

      #-------------------------------------------------------------------
      # Config class for a given Trigger
      #-------------------------------------------------------------------

      # Internal unique name for this trigger
      #
      # Note: This is for internal use only.
      #
      # @return [String]
      attr_reader :id

      # Name for the given Trigger. Defaults to nil.
      #
      # @return [String]
      attr_accessor :name

      # Command to fire the trigger on
      #
      # @return [Symbol]
      attr_reader :command

      # A string to print at the WARN level
      #
      # @return [String]
      attr_accessor :info

      # A string to print at the WARN level
      #
      # @return [String]
      attr_accessor :warn

      # Determines what how a Trigger should behave if it runs into an error.
      # Defaults to :halt, otherwise can only be set to :continue.
      #
      # @return [Symbol]
      attr_accessor :on_error

      # If set, will not run trigger for the configured Vagrant commands.
      #
      # @return [Symbol, Array]
      attr_accessor :ignore

      # If set, will only run trigger for guests that match keys for this parameter.
      #
      # @return [String, Regex, Array]
      attr_accessor :only_on

      # A local inline or file script to execute for the trigger
      #
      # @return [Hash]
      attr_accessor :run

      # A remote inline or file script to execute for the trigger
      #
      # @return [Hash]
      attr_accessor :run_remote

      # If set, will not run trigger for the configured Vagrant commands.
      #
      # @return [Integer, Array]
      attr_accessor :exit_codes

      def initialize(command)
        @logger = Log4r::Logger.new("vagrant::config::vm::trigger::config")

        @name = UNSET_VALUE
        @info = UNSET_VALUE
        @warn = UNSET_VALUE
        @on_error = UNSET_VALUE
        @ignore = UNSET_VALUE
        @only_on = UNSET_VALUE
        @run = UNSET_VALUE
        @run_remote = UNSET_VALUE
        @exit_codes = UNSET_VALUE

        # Internal options
        @id = SecureRandom.uuid
        @command = command.to_sym

        @logger.debug("Trigger defined for command: #{command}")
      end

      def finalize!
        # Ensure all config options are set to nil or default value if untouched
        # by user
        @name = nil if @name == UNSET_VALUE
        @info = nil if @info == UNSET_VALUE
        @warn = nil if @warn == UNSET_VALUE
        @on_error = DEFAULT_ON_ERROR if @on_error == UNSET_VALUE
        @ignore = [] if @ignore == UNSET_VALUE
        @run = nil if @run == UNSET_VALUE
        @run_remote = nil if @run_remote == UNSET_VALUE
        @only_on = nil if @only_on == UNSET_VALUE
        @exit_codes = DEFAULT_EXIT_CODE if @exit_codes == UNSET_VALUE

        # these values are expected to always be an Array internally,
        # but can be set as a single String or Symbol
        #
        # Guests are stored internally as strings
        if @only_on
          @only_on = Array(@only_on)
        end

        # Commands must be stored internally as symbols
        if @ignore
          @ignore = Array(@ignore)
          @ignore.map! { |i| i.to_sym }
        end

        if @exit_codes
          @exit_codes = Array(@exit_codes)
        end

        # Convert @run and @run_remote to be a "Shell provisioner" config
        if @run && @run.is_a?(Hash)
          # Powershell args and privileged for run commands is currently not supported
          # so by default use empty string or false if unset. This helps the validate
          # function determine if the setting was purposefully set, to print a warning
          if !@run.key?(:powershell_args)
            @run[:powershell_args] = ""
          end

          if !@run.key?(:privileged)
            @run[:privileged] = false
          end

          new_run = VagrantPlugins::Shell::Config.new
          new_run.set_options(@run)
          new_run.finalize!
          @run = new_run
        end

        if @run_remote && @run_remote.is_a?(Hash)
          new_run = VagrantPlugins::Shell::Config.new
          new_run.set_options(@run_remote)
          new_run.finalize!
          @run_remote = new_run
        end

      end

      # @return [Array] array of strings of error messages from config option validation
      def validate(machine)
        errors = _detected_errors

        commands = []
        Vagrant.plugin("2").manager.commands.each do |key,data|
          commands.push(key)
        end

        if !commands.include?(@command) && @command != :all
          machine.ui.warn(I18n.t("vagrant.config.triggers.bad_command_warning",
                                cmd: @command))
        end

        if @run
          errorz = @run.validate(machine)
          errors.concat errorz["shell provisioner"] if !errorz.empty?

          if @run.privileged == true
            machine.ui.warn(I18n.t("vagrant.config.triggers.privileged_ignored",
                                  command: @command))
          end

          if @run.powershell_args != ""
            machine.ui.warn(I18n.t("vagrant.config.triggers.powershell_args_ignored"))
          end
        end

        if @run_remote
          errorz = @run_remote.validate(machine)
          errors.concat errorz["shell provisioner"] if !errorz.empty?
        end

        if @name && !@name.is_a?(String)
          errors << I18n.t("vagrant.config.triggers.name_bad_type", cmd: @command)
        end

        if @info && !@info.is_a?(String)
          errors << I18n.t("vagrant.config.triggers.info_bad_type", cmd: @command)
        end

        if @warn && !@warn.is_a?(String)
          errors << I18n.t("vagrant.config.triggers.warn_bad_type", cmd: @command)
        end

        if @on_error != :halt
          if @on_error != :continue
            errors << I18n.t("vagrant.config.triggers.on_error_bad_type", cmd: @command)
          end
        end

        if @exit_codes
          if !@exit_codes.all? {|i| i.is_a?(Integer)}
            errors << I18n.t("vagrant.config.triggers.exit_codes_bad_type", cmd: @command)
          end
        end

        errors
      end

      # The String representation of this Trigger.
      #
      # @return [String]
      def to_s
        "trigger config"
      end
    end
  end
end
