module Msf::DBManager::Loot
  #
  # Find or create a loot matching this type/data
  #
  def find_or_create_loot(opts)
    report_loot(opts)
  end

  #
  # This methods returns a list of all loot in the database
  #
  def loots(opts)
    data = opts.delete(:data)
    # Remove path from search conditions as this won't accommodate remote data
    # service usage where the client and server storage locations differ.
    opts.delete(:path)
    search_term = opts.delete(:search_term)

    ::ActiveRecord::Base.connection_pool.with_connection {
      wspace = Msf::Util::DBManager.process_opts_workspace(opts, framework)
      opts[:workspace_id] = wspace.id

      if search_term && !search_term.empty?
        column_search_conditions = Msf::Util::DBManager.create_all_column_search_conditions(Mdm::Loot, search_term)
        results = Mdm::Loot.includes(:host).where(opts).where(column_search_conditions)
      else
        results = Mdm::Loot.includes(:host).where(opts)
      end

      # Compare the deserialized data from the DB to the search data since the column is serialized.
      unless data.nil?
        results = results.select { |loot| loot.data == data }
      end

      results
    }
  end
  alias_method :loot, :loots

  def report_loot(opts)
    return if not active
  ::ActiveRecord::Base.connection_pool.with_connection {
    wspace = Msf::Util::DBManager.process_opts_workspace(opts, framework)
    path = opts.delete(:path) || (raise RuntimeError, "A loot :path is required")

    host = nil
    addr = nil

    # Report the host so it's there for the Proc to use below
    if opts[:host]
      if opts[:host].kind_of? ::Mdm::Host
        host = opts[:host]
      else
        host = report_host({:workspace => wspace, :host => opts[:host]})
        addr = Msf::Util::Host.normalize_host(opts[:host])
      end
    end

    ret = {}

    ltype  = opts.delete(:type) || opts.delete(:ltype) || (raise RuntimeError, "A loot :type or :ltype is required")
    ctype  = opts.delete(:ctype) || opts.delete(:content_type) || 'text/plain'
    name   = opts.delete(:name)
    info   = opts.delete(:info)
    data   = opts[:data]
    loot   = wspace.loots.new

    if host
      loot.host_id = host[:id]
    end
    if opts[:service] and opts[:service].kind_of? ::Mdm::Service
      loot.service_id = opts[:service][:id]
    end

    loot.path         = path
    loot.ltype        = ltype
    loot.content_type = ctype
    loot.data         = data
    loot.name         = name if name
    loot.info         = info if info
    loot.workspace    = wspace
    msf_import_timestamps(opts,loot)
    loot.save!

    ret[:loot] = loot
  }
  end

  # Update the attributes of a Loot entry with the values in opts.
  # The values in opts should match the attributes to update.
  #
  # @param opts [Hash] Hash containing the updated values. Key should match the attribute to update. Must contain :id of record to update.
  # @return [Mdm::Loot] The updated Mdm::Loot object.
  def update_loot(opts)
    ::ActiveRecord::Base.connection_pool.with_connection {
      wspace = Msf::Util::DBManager.process_opts_workspace(opts, framework, false)
      opts[:workspace] = wspace if wspace

      id = opts.delete(:id)
      Mdm::Loot.update(id, opts)
    }
  end

  # Deletes Loot entries based on the IDs passed in.
  #
  # @param opts[:ids] [Array] Array containing Integers corresponding to the IDs of the Loot entries to delete.
  # @return [Array] Array containing the Mdm::Loot objects that were successfully deleted.
  def delete_loot(opts)
    raise ArgumentError.new("The following options are required: :ids") if opts[:ids].nil?

    ::ActiveRecord::Base.connection_pool.with_connection {
      deleted = []
      opts[:ids].each do |loot_id|
        loot = Mdm::Loot.find(loot_id)
        begin
          deleted << loot.destroy
        rescue # refs suck
          elog("Forcibly deleting #{loot}")
          deleted << loot.delete
        end
      end

      return deleted
    }
  end
end