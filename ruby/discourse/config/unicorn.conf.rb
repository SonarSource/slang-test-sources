# See http://unicorn.bogomips.org/Unicorn/Configurator.html

if ENV["LOGSTASH_UNICORN_URI"]
  require_relative '../lib/discourse_logstash_logger'
  logger DiscourseLogstashLogger.logger(uri: ENV['LOGSTASH_UNICORN_URI'], type: :unicorn)
end

# enable out of band gc out of the box, it is low risk and improves perf a lot
ENV['UNICORN_ENABLE_OOBGC'] ||= "1"

discourse_path = File.expand_path(File.expand_path(File.dirname(__FILE__)) + "/../")

# tune down if not enough ram
worker_processes (ENV["UNICORN_WORKERS"] || 3).to_i

working_directory discourse_path

# listen "#{discourse_path}/tmp/sockets/unicorn.sock"
listen (ENV["UNICORN_PORT"] || 3000).to_i

# nuke workers after 30 seconds instead of 60 seconds (the default)
timeout 30

# feel free to point this anywhere accessible on the filesystem
pid (ENV["UNICORN_PID_PATH"] || "#{discourse_path}/tmp/pids/unicorn.pid")

# By default, the Unicorn logger will write to stderr.
# Additionally, some applications/frameworks log to stderr or stdout,
# so prevent them from going to /dev/null when daemonized here:
stderr_path "#{discourse_path}/log/unicorn.stderr.log"
stdout_path "#{discourse_path}/log/unicorn.stdout.log"

# important for Ruby 2.0
preload_app true

# Enable this flag to have unicorn test client connections by writing the
# beginning of the HTTP headers before calling the application.  This
# prevents calling the application for connections that have disconnected
# while queued.  This is only guaranteed to detect clients on the same
# host unicorn runs on, and unlikely to detect disconnects even on a
# fast LAN.
check_client_connection false

@stats_socket_dir = ENV["UNICORN_STATS_SOCKET_DIR"]

def clean_up_stats_socket(server, pid)
  if @stats_socket_dir.present?
    name = "#{@stats_socket_dir}/#{pid}.sock"
    FileUtils.rm_f(name)
    server.logger.info "Cleaned up stats socket at #{name}"
  end
rescue => e
  server.logger.warn "Failed to clean up stats socket #{e}"
end

def start_stats_socket(server)
  if @stats_socket_dir.present?
    name = "#{@stats_socket_dir}/#{Process.pid}.sock"
    StatsSocket.new(name).start
    server.logger.info "Started stats socket at #{name}"
  end
rescue => e
  server.logger.warn "Failed to start stats socket #{e}"
end

initialized = false
before_fork do |server, worker|

  unless initialized
    # load up the yaml for the localization bits, in master process
    I18n.t(:posts)

    # load up all models and schema
    (ActiveRecord::Base.connection.tables - %w[schema_migrations versions]).each do |table|
      table.classify.constantize.first rescue nil
    end

    # router warm up
    Rails.application.routes.recognize_path('abc') rescue nil

    if @stats_socket_dir.present?
      server.logger.info "Initializing stats socket at #{@stats_socket_dir}"
      begin
        require 'stats_socket'
        FileUtils.mkdir_p @stats_socket_dir
        FileUtils.rm_f Dir.glob("#{@stats_socket_dir}/*.sock")
        start_stats_socket(server)
      rescue => e
        server.logger.info "Failed to initialize stats socket dir #{e}"
      end
    end

    # preload discourse version
    Discourse.git_version
    Discourse.git_branch
    Discourse.full_version

    # get rid of rubbish so we don't share it
    GC.start

    initialized = true

    supervisor = ENV['UNICORN_SUPERVISOR_PID'].to_i
    if supervisor > 0
      Thread.new do
        while true
          unless File.exists?("/proc/#{supervisor}")
            puts "Kill self supervisor is gone"
            Process.kill "TERM", Process.pid
          end
          sleep 2
        end
      end
    end

    sidekiqs = ENV['UNICORN_SIDEKIQS'].to_i
    if sidekiqs > 0
      puts "Starting up #{sidekiqs} supervised sidekiqs"

      require 'demon/sidekiq'
      Demon::Sidekiq.after_fork do
        start_stats_socket(server) if @stats_socket_dir
        DiscourseEvent.trigger(:sidekiq_fork_started)
      end

      Demon::Sidekiq.start(sidekiqs)

      Signal.trap("SIGTSTP") do
        STDERR.puts "#{Time.now}: Issuing stop to sidekiq"
        Demon::Sidekiq.stop
      end

      class ::Unicorn::HttpServer
        alias :master_sleep_orig :master_sleep

        def max_rss
          rss = `ps -eo rss,args | grep sidekiq | grep -v grep | awk '{print $1}'`
            .split("\n")
            .map(&:to_i)
            .max

          rss ||= 0

          rss * 1024
        end

        def max_allowed_size
          [ENV['UNICORN_SIDEKIQ_MAX_RSS'].to_i, 500].max.megabytes
        end

        def out_of_memory?
          max_rss > max_allowed_size
        end

        def force_kill_rogue_sidekiq
          info = `ps -eo pid,rss,args | grep sidekiq | grep -v grep | awk '{print $1,$2}'`
          info.split("\n").each do |row|
            pid, mem = row.split(" ").map(&:to_i)
            if pid > 0 && (mem * 1024) > max_allowed_size
              Rails.logger.warn "Detected rogue Sidekiq pid #{pid} mem #{mem * 1024}, killing"
              Process.kill("KILL", pid) rescue nil
            end
          end
        end

        def check_sidekiq_heartbeat
          @sidekiq_heartbeat_interval ||= 30.minutes
          @sidekiq_next_heartbeat_check ||= Time.new.to_i + @sidekiq_heartbeat_interval

          if @sidekiq_next_heartbeat_check < Time.new.to_i

            last_heartbeat = Jobs::RunHeartbeat.last_heartbeat
            restart = false

            if out_of_memory?
              Rails.logger.warn("Sidekiq is consuming too much memory (using: %0.2fM) for '%s', restarting" % [(max_rss.to_f / 1.megabyte), ENV["DISCOURSE_HOSTNAME"]])
              restart = true
            end

            if last_heartbeat < Time.new.to_i - @sidekiq_heartbeat_interval
              STDERR.puts "Sidekiq heartbeat test failed, restarting"
              Rails.logger.warn "Sidekiq heartbeat test failed, restarting"

              restart = true
            end
            @sidekiq_next_heartbeat_check = Time.new.to_i + @sidekiq_heartbeat_interval

            if restart
              Demon::Sidekiq.restart
              sleep 10
              force_kill_rogue_sidekiq
            end
            $redis._client.disconnect
          end
        end

        def master_sleep(sec)
          Demon::Sidekiq.ensure_running
          check_sidekiq_heartbeat

          master_sleep_orig(sec)
        end
      end
    end

  end

  $redis._client.disconnect

  # Throttle the master from forking too quickly by sleeping.  Due
  # to the implementation of standard Unix signal handlers, this
  # helps (but does not completely) prevent identical, repeated signals
  # from being lost when the receiving process is busy.
  sleep 1
end

after_worker_exit do |server, worker, status|
  clean_up_stats_socket(server, status.pid)
end

after_fork do |server, worker|
  start_stats_socket(server)

  DiscourseEvent.trigger(:web_fork_started)

  # warm up v8 after fork, that way we do not fork a v8 context
  # it may cause issues if bg threads in a v8 isolate randomly stop
  # working due to fork
  Discourse.after_fork
  begin
    PrettyText.cook("warm up **pretty text**")
  rescue => e
    Rails.logger.error("Failed to warm up pretty text: #{e}")
  end
end
