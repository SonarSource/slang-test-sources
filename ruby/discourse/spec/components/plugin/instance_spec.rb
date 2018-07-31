require 'rails_helper'
require_dependency 'plugin/instance'

describe Plugin::Instance do

  after do
    DiscoursePluginRegistry.reset!
  end

  context "find_all" do
    it "can find plugins correctly" do
      plugins = Plugin::Instance.find_all("#{Rails.root}/spec/fixtures/plugins")
      expect(plugins.count).to eq(2)
      plugin = plugins[1]

      expect(plugin.name).to eq("plugin-name")
      expect(plugin.path).to eq("#{Rails.root}/spec/fixtures/plugins/my_plugin/plugin.rb")
    end

    it "does not blow up on missing directory" do
      plugins = Plugin::Instance.find_all("#{Rails.root}/frank_zappa")
      expect(plugins.count).to eq(0)
    end
  end

  context "enabling/disabling" do

    it "is enabled by default" do
      expect(Plugin::Instance.new.enabled?).to eq(true)
    end

    context "with a plugin that extends things" do

      class Trout; end
      class TroutSerializer < ApplicationSerializer; end

      class TroutPlugin < Plugin::Instance
        attr_accessor :enabled
        def enabled?; @enabled; end
      end

      before do
        @plugin = TroutPlugin.new
        @trout = Trout.new

        # New method
        @plugin.add_to_class(:trout, :status?) { "evil" }

        # DiscourseEvent
        @hello_count = 0
        @increase_count = -> { @hello_count += 1 }
        @set = @plugin.on(:hello, &@increase_count)

        # Serializer
        @plugin.add_to_serializer(:trout, :scales) { 1024 }
        @serializer = TroutSerializer.new(@trout)
      end

      after do
        DiscourseEvent.off(:hello, &@set.first)
      end

      it "checks enabled/disabled functionality for extensions" do

        # with an enabled plugin
        @plugin.enabled = true
        expect(@trout.status?).to eq("evil")
        DiscourseEvent.trigger(:hello)
        expect(@hello_count).to eq(1)
        expect(@serializer.scales).to eq(1024)
        expect(@serializer.include_scales?).to eq(true)

        # When a plugin is disabled
        @plugin.enabled = false
        expect(@trout.status?).to eq(nil)
        DiscourseEvent.trigger(:hello)
        expect(@hello_count).to eq(1)
        expect(@serializer.scales).to eq(1024)
        expect(@serializer.include_scales?).to eq(false)

      end
    end
  end

  context "register asset" do
    it "populates the DiscoursePluginRegistry" do
      plugin = Plugin::Instance.new nil, "/tmp/test.rb"
      plugin.register_asset("test.css")
      plugin.register_asset("test2.css")

      plugin.send :register_assets!

      expect(DiscoursePluginRegistry.mobile_stylesheets.count).to eq(0)
      expect(DiscoursePluginRegistry.stylesheets.count).to eq(2)
    end

    it "remaps vendored_core_pretty_text asset" do
      plugin = Plugin::Instance.new nil, "/tmp/test.rb"
      plugin.register_asset("moment.js", :vendored_core_pretty_text)

      plugin.send :register_assets!

      expect(DiscoursePluginRegistry.vendored_core_pretty_text.first).to eq("lib/javascripts/moment.js")
    end
  end

  context "register service worker" do
    it "populates the DiscoursePluginRegistry" do
      plugin = Plugin::Instance.new nil, "/tmp/test.rb"
      plugin.register_service_worker("test.js")
      plugin.register_service_worker("test2.js")

      plugin.send :register_service_workers!

      expect(DiscoursePluginRegistry.service_workers.count).to eq(2)
    end
  end

  context "#add_report" do
    it "adds a report" do
      plugin = Plugin::Instance.new nil, "/tmp/test.rb"
      plugin.add_report("readers") {}

      expect(Report.respond_to?(:report_readers)).to eq(true)
    end
  end

  it 'patches the enabled? function for auth_providers if not defined' do
    SiteSetting.stubs(:ubuntu_login_enabled).returns(false)

    plugin = Plugin::Instance.new

    # No enabled_site_setting
    authenticator = Auth::Authenticator.new
    plugin.auth_provider(authenticator: authenticator)
    plugin.notify_after_initialize
    expect(authenticator.enabled?).to eq(true)

    # With enabled site setting
    authenticator = Auth::Authenticator.new
    plugin.auth_provider(enabled_setting: 'ubuntu_login_enabled', authenticator: authenticator)
    plugin.notify_after_initialize
    expect(authenticator.enabled?).to eq(false)

    # Defines own method
    SiteSetting.stubs(:ubuntu_login_enabled).returns(true)
    authenticator = Class.new(Auth::Authenticator) do
      def enabled?
        false
      end
    end.new
    plugin.auth_provider(enabled_setting: 'ubuntu_login_enabled', authenticator: authenticator)
    plugin.notify_after_initialize
    expect(authenticator.enabled?).to eq(false)
  end

  context "activate!" do
    before do
      SiteSetting.stubs(:ubuntu_login_enabled).returns(false)
    end

    it "can activate plugins correctly" do
      plugin = Plugin::Instance.new
      plugin.path = "#{Rails.root}/spec/fixtures/plugins/my_plugin/plugin.rb"
      junk_file = "#{plugin.auto_generated_path}/junk"

      plugin.ensure_directory(junk_file)
      File.open("#{plugin.auto_generated_path}/junk", "w") { |f| f.write("junk") }
      plugin.activate!

      # calls ensure_assets! make sure they are there
      expect(plugin.assets.count).to eq(1)
      plugin.assets.each do |a, opts|
        expect(File.exists?(a)).to eq(true)
      end

      # ensure it cleans up all crap in autogenerated directory
      expect(File.exists?(junk_file)).to eq(false)
    end

    it "registers auth providers correctly" do
      plugin = Plugin::Instance.new
      plugin.path = "#{Rails.root}/spec/fixtures/plugins/my_plugin/plugin.rb"
      plugin.activate!

      expect(plugin.auth_providers.count).to eq(1)
      auth_provider = plugin.auth_providers[0]
      expect(auth_provider.authenticator.name).to eq('ubuntu')
      expect(DiscoursePluginRegistry.auth_providers.count).to eq(1)
    end

    it "finds all the custom assets" do
      plugin = Plugin::Instance.new
      plugin.path = "#{Rails.root}/spec/fixtures/plugins/my_plugin/plugin.rb"

      plugin.register_asset("test.css")
      plugin.register_asset("test2.scss")
      plugin.register_asset("mobile.css", :mobile)
      plugin.register_asset("desktop.css", :desktop)
      plugin.register_asset("desktop2.css", :desktop)

      plugin.register_asset("variables1.scss", :variables)
      plugin.register_asset("variables2.scss", :variables)

      plugin.register_asset("code.js")

      plugin.register_asset("my_admin.js", :admin)
      plugin.register_asset("my_admin2.js", :admin)

      plugin.activate!

      expect(DiscoursePluginRegistry.javascripts.count).to eq(2)
      expect(DiscoursePluginRegistry.admin_javascripts.count).to eq(2)
      expect(DiscoursePluginRegistry.desktop_stylesheets.count).to eq(2)
      expect(DiscoursePluginRegistry.sass_variables.count).to eq(2)
      expect(DiscoursePluginRegistry.stylesheets.count).to eq(2)
      expect(DiscoursePluginRegistry.mobile_stylesheets.count).to eq(1)
    end
  end

  context "serialized_current_user_fields" do
    before do
      DiscoursePluginRegistry.serialized_current_user_fields << "has_car"
    end

    after do
      DiscoursePluginRegistry.serialized_current_user_fields.delete "has_car"
    end

    it "correctly serializes custom user fields" do
      DiscoursePluginRegistry.serialized_current_user_fields << "has_car"
      user = Fabricate(:user)
      user.custom_fields["has_car"] = "true"
      user.save!

      payload = JSON.parse(CurrentUserSerializer.new(user, scope: Guardian.new(user)).to_json)
      expect(payload["current_user"]["custom_fields"]["has_car"]).to eq("true")

      payload = JSON.parse(UserSerializer.new(user, scope: Guardian.new(user)).to_json)
      expect(payload["user"]["custom_fields"]["has_car"]).to eq("true")

      UserCustomField.destroy_all
      user.reload

      payload = JSON.parse(CurrentUserSerializer.new(user, scope: Guardian.new(user)).to_json)
      expect(payload["current_user"]["custom_fields"]).to eq({})

      payload = JSON.parse(UserSerializer.new(user, scope: Guardian.new(user)).to_json)
      expect(payload["user"]["custom_fields"]).to eq({})
    end
  end

  context "register_color_scheme" do
    it "can add a color scheme for the first time" do
      plugin = Plugin::Instance.new nil, "/tmp/test.rb"
      expect {
        plugin.register_color_scheme("Purple", primary: 'EEE0E5')
        plugin.notify_after_initialize
      }.to change { ColorScheme.count }.by(1)
      expect(ColorScheme.where(name: "Purple")).to be_present
    end

    it "doesn't add the same color scheme twice" do
      Fabricate(:color_scheme, name: "Halloween")
      plugin = Plugin::Instance.new nil, "/tmp/test.rb"
      expect {
        plugin.register_color_scheme("Halloween", primary: 'EEE0E5')
        plugin.notify_after_initialize
      }.to_not change { ColorScheme.count }
    end
  end

  describe '.register_seedfu_fixtures' do
    it "should add the new path to SeedFu's fixtures path" do
      plugin = Plugin::Instance.new nil, "/tmp/test.rb"
      plugin.register_seedfu_fixtures(['some_path'])
      plugin.register_seedfu_fixtures('some_path2')

      expect(SeedFu.fixture_paths).to include('some_path')
      expect(SeedFu.fixture_paths).to include('some_path2')
    end
  end

  describe '#add_model_callback' do
    let(:metadata) do
      metadata = Plugin::Metadata.new
      metadata.name = 'test'
      metadata
    end

    let(:plugin_instance) do
      plugin = Plugin::Instance.new(nil, "/tmp/test.rb")
      plugin.metadata = metadata
      plugin
    end

    it 'should add the right callback' do
      called = 0

      plugin_instance.add_model_callback(User, :after_create) do
        called += 1
      end

      user = Fabricate(:user)

      expect(called).to eq(1)

      user.update_attributes!(username: 'some_username')

      expect(called).to eq(1)
    end

    it 'should add the right callback with options' do
      called = 0

      plugin_instance.add_model_callback(User, :after_commit, on: :create) do
        called += 1
      end

      user = Fabricate(:user)

      expect(called).to eq(1)

      user.update_attributes!(username: 'some_username')

      expect(called).to eq(1)
    end
  end

  context "locales" do
    let(:plugin_path) { "#{Rails.root}/spec/fixtures/plugins/custom_locales" }
    let!(:plugin) { Plugin::Instance.new(nil, "#{plugin_path}/plugin.rb") }
    let(:plural) do
      {
        keys: [:one, :few, :other],
        rule: lambda do |n|
          return :one if n == 1
          return :few if n < 10
          :other
        end
      }
    end

    def register_locale(locale, opts)
      plugin.register_locale(locale, opts)
      plugin.activate!

      DiscoursePluginRegistry.locales[locale]
    end

    it "enables the registered locales only on activate" do
      plugin.register_locale("foo", name: "Foo", nativeName: "Foo Bar", plural: plural)
      plugin.register_locale("es_MX", name: "Spanish (Mexico)", nativeName: "Español (México)", fallbackLocale: "es")
      expect(DiscoursePluginRegistry.locales.count).to eq(0)

      plugin.activate!
      expect(DiscoursePluginRegistry.locales.count).to eq(2)
    end

    it "allows finding the locale by string and symbol" do
      register_locale("foo", name: "Foo", nativeName: "Foo Bar", plural: plural)

      expect(DiscoursePluginRegistry.locales).to have_key(:foo)
      expect(DiscoursePluginRegistry.locales).to have_key('foo')
    end

    it "correctly registers a new locale" do
      locale = register_locale("foo", name: "Foo", nativeName: "Foo Bar", plural: plural)

      expect(DiscoursePluginRegistry.locales.count).to eq(1)
      expect(DiscoursePluginRegistry.locales).to have_key(:foo)

      expect(locale[:fallbackLocale]).to be_nil
      expect(locale[:message_format]).to eq(["foo", "#{plugin_path}/lib/javascripts/locale/message_format/foo.js"])
      expect(locale[:moment_js]).to eq(["foo", "#{plugin_path}/lib/javascripts/locale/moment_js/foo.js"])
      expect(locale[:plural]).to eq(plural.with_indifferent_access)

      expect(Rails.configuration.assets.precompile).to include("locales/foo.js")
    end

    it "correctly registers a new locale using a fallback locale" do
      locale = register_locale("es_MX", name: "Spanish (Mexico)", nativeName: "Español (México)", fallbackLocale: "es")

      expect(DiscoursePluginRegistry.locales.count).to eq(1)
      expect(DiscoursePluginRegistry.locales).to have_key(:es_MX)

      expect(locale[:fallbackLocale]).to eq("es")
      expect(locale[:message_format]).to eq(["es", "#{Rails.root}/lib/javascripts/locale/es.js"])
      expect(locale[:moment_js]).to eq(["es", "#{Rails.root}/lib/javascripts/moment_locale/es.js"])
      expect(locale[:plural]).to be_nil

      expect(Rails.configuration.assets.precompile).to include("locales/es_MX.js")
    end

    it "correctly registers a new locale when some files exist in core" do
      locale = register_locale("tlh", name: "Klingon", nativeName: "tlhIngan Hol", plural: plural)

      expect(DiscoursePluginRegistry.locales.count).to eq(1)
      expect(DiscoursePluginRegistry.locales).to have_key(:tlh)

      expect(locale[:fallbackLocale]).to be_nil
      expect(locale[:message_format]).to eq(["tlh", "#{plugin_path}/lib/javascripts/locale/message_format/tlh.js"])
      expect(locale[:moment_js]).to eq(["tlh", "#{Rails.root}/lib/javascripts/moment_locale/tlh.js"])
      expect(locale[:plural]).to eq(plural.with_indifferent_access)

      expect(Rails.configuration.assets.precompile).to include("locales/tlh.js")
    end

    it "does not register a new locale when the fallback locale does not exist" do
      register_locale("bar", name: "Bar", nativeName: "Bar", fallbackLocale: "foo")
      expect(DiscoursePluginRegistry.locales.count).to eq(0)
    end

    [
      "config/locales/client.foo.yml",
      "config/locales/server.foo.yml",
      "lib/javascripts/locale/message_format/foo.js",
      "lib/javascripts/locale/moment_js/foo.js",
      "assets/locales/foo.js.erb"
    ].each do |path|
      it "does not register a new locale when #{path} is missing" do
        path = "#{plugin_path}/#{path}"
        File.stubs('exist?').returns(false)
        File.stubs('exist?').with(regexp_matches(/#{Regexp.quote(plugin_path)}.*/)).returns(true)
        File.stubs('exist?').with(path).returns(false)

        register_locale("foo", name: "Foo", nativeName: "Foo Bar", plural: plural)
        expect(DiscoursePluginRegistry.locales.count).to eq(0)
      end
    end
  end

  describe '#enabled_site_setting_filter' do
    describe 'when filter is blank' do
      it 'should return the right value' do
        expect(Plugin::Instance.new.enabled_site_setting_filter).to eq(nil)
      end
    end

    it 'should set the right value' do
      instance = Plugin::Instance.new
      instance.enabled_site_setting_filter('test')

      expect(instance.enabled_site_setting_filter).to eq('test')
    end
  end

end
