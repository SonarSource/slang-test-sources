require 'open3'
require 'rex/ui'
require 'rex/logging'
require 'metasploit/framework/data_service/proxy/data_proxy_auto_loader'

#
# Holds references to data services (@see Metasploit::Framework::DataService)
# and forwards data to the implementation set as current.
#
module Metasploit
module Framework
module DataService
class DataProxy
  include DataProxyAutoLoader

  attr_reader :usable

  def initialize(opts = {})
    @data_services = {}
    @data_service_id = 0
    @usable = false
    setup(opts)
  end

  #
  # Returns current error state
  #
  def error
    return @error if (@error)
    return @current_data_service.error if @current_data_service && !@current_data_service.error.nil?
    return 'unknown'
  end

  def is_local?
    if @current_data_service
      return @current_data_service.is_local?
    end

    return false
  end

  #
  # Determines if the data service is active
  #
  def active
    if @current_data_service
      return @current_data_service.active
    end

    return false
  end

  #
  # Registers the specified data service with the proxy
  # and immediately sets it as the primary if active
  #
  def register_data_service(data_service)
    validate(data_service)
    data_service_id = @data_service_id += 1
    @data_services[data_service_id] = data_service
    set_data_service(data_service_id)
  end

  #
  # Delete the specified data service
  #
  def delete_data_service(data_service_id)
    raise ArgumentError.new('Cannot delete data service id: 1') if data_service_id.to_i == 1

    data_service = @data_services.delete(data_service_id.to_i)
    if data_service.nil?
      raise "Data service with id: #{data_service_id} does not exist"
    end

    if @current_data_service == data_service
      # set the current data service to the first data service created
      @current_data_service = @data_services[1]
    end
  end

  #
  # Set the data service to be used
  #
  def set_data_service(data_service_id)
    data_service = @data_services[data_service_id.to_i]
    if data_service.nil?
      raise "Data service with id: #{data_service_id} does not exist"
    end

    if !data_service.is_local? && !data_service.active
      raise "Data service #{data_service.name} is not online, and won't be set as active"
    end

    prev_data_service = @current_data_service
    @current_data_service = data_service
    # reset the previous data service's active flag if it is remote
    # to ensure checks are performed the next time it is set
    if !prev_data_service.nil? && !prev_data_service.is_local?
      prev_data_service.active = false
    end
  end

  #
  # Retrieves metadata about the data services
  #
  def get_services_metadata()
    services_metadata = []
    @data_services.each_key {|key|
      name = @data_services[key].name
      active = !@current_data_service.nil? && name == @current_data_service.name
      is_local = @data_services[key].is_local?
      services_metadata << Metasploit::Framework::DataService::Metadata.new(key, name, active, is_local)
    }

    services_metadata
  end

  #
  # Used to bridge the local db
  #
  def method_missing(method, *args, &block)
    unless @current_data_service.nil?
      @current_data_service.send(method, *args, &block)
    end
  end

  def respond_to?(method_name, include_private=false)
    unless @current_data_service.nil?
      return @current_data_service.respond_to?(method_name, include_private)
    end

    false
  end

  def get_data_service
    raise 'No registered data_service' unless @current_data_service
    return @current_data_service
  end

  def log_error(exception, ui_message)
    elog "#{ui_message}: #{exception.message}"
    exception.backtrace.each { |line| elog "#{line}" }
    # TODO: We should try to surface the original exception, instead of just a generic one.
    # This should not display the full backtrace, only the message.
    raise Exception, "#{ui_message}: #{exception.message}. See log for more details."
  end

  # Adds a valid workspace value to the opts hash before sending on to the data layer.
  #
  # @param [Hash] opts The opts hash that will be passed to the data layer.
  # @param [String] wspace A specific workspace name to add to the opts hash.
  # @return [Hash] The opts hash with a valid :workspace value added.
  def add_opts_workspace(opts, wspace = nil)
    # Some methods use the key :wspace. Let's standardize on :workspace and clean it up here.
    opts[:workspace] = opts.delete(:wspace) unless opts[:wspace].nil?

    # If the user passed in a specific workspace then use that in opts
    opts[:workspace] = wspace if wspace

    # We only want to pass the workspace name, so grab it if it is currently an object.
    if opts[:workspace] && opts[:workspace].is_a?(::Mdm::Workspace)
      opts[:workspace] = opts[:workspace].name
    end

    # If we still don't have a :workspace value, just set it to the current workspace.
    opts[:workspace] = workspace.name if opts[:workspace].nil?

    opts
  end

  #######
  private
  #######

  def setup(opts)
    begin
      db_manager = opts.delete(:db_manager)
      if !db_manager.nil?
        register_data_service(db_manager)
        @usable = true
      else
        @error = 'disabled'
      end
    rescue => e
      raise "Unable to initialize data service: #{e.message}"
    end
  end

  def validate(data_service)
    raise "Invalid data_service: #{data_service.class}, not of type Metasploit::Framework::DataService" unless data_service.is_a? (Metasploit::Framework::DataService)
    raise 'Cannot register null data service data_service' unless data_service
    raise 'Data Service already exists' if data_service_exist?(data_service)
  end

  def data_service_exist?(data_service)
    @data_services.each_value{|value|
      if (value.name == data_service.name)
        return true
      end
    }

    return false
  end

end
end
end
end
