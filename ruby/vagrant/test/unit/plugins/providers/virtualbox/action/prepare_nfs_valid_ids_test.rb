require_relative "../base"

describe VagrantPlugins::ProviderVirtualBox::Action::PrepareNFSValidIds do
  include_context "unit"
  include_context "virtualbox"

  let(:iso_env) do
    # We have to create a Vagrantfile so there is a root path
    env = isolated_environment
    env.vagrantfile("")
    env.create_vagrant_env
  end

  let(:machine) do
    iso_env.machine(iso_env.machine_names[0], :dummy).tap do |m|
      allow(m.provider).to receive(:driver).and_return(driver)
    end
  end

  let(:env)    {{ machine: machine }}
  let(:app)    { lambda { |*args| }}
  let(:driver) { double("driver") }

  subject { described_class.new(app, env) }

  before do
    allow(driver).to receive(:read_vms).and_return({})
  end

  it "calls the next action in the chain" do
    called = false
    app = lambda { |*args| called = true }

    action = described_class.new(app, env)
    action.call(env)

    expect(called).to eq(true)
  end

  it "sets nfs_valid_ids" do
    hash = {"foo" => "1", "bar" => "4"}
    allow(driver).to receive(:read_vms).and_return(hash)

    subject.call(env)

    expect(env[:nfs_valid_ids]).to eql(hash.values)
  end
end
