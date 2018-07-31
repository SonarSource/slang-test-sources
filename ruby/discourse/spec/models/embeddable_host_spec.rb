require 'rails_helper'

describe EmbeddableHost do

  it "trims http" do
    eh = EmbeddableHost.new(host: 'http://example.com')
    expect(eh).to be_valid
    expect(eh.host).to eq('example.com')
  end

  it "trims https" do
    eh = EmbeddableHost.new(host: 'https://example.com')
    expect(eh).to be_valid
    expect(eh.host).to eq('example.com')
  end

  it "trims paths" do
    eh = EmbeddableHost.new(host: 'https://example.com/1234/45')
    expect(eh).to be_valid
    expect(eh.host).to eq('example.com')
  end

  it "supports ip addresses" do
    eh = EmbeddableHost.new(host: '192.168.0.1')
    expect(eh).to be_valid
    expect(eh.host).to eq('192.168.0.1')
  end

  it "supports localhost" do
    eh = EmbeddableHost.new(host: 'localhost')
    expect(eh).to be_valid
    expect(eh.host).to eq('localhost')
  end

  it "supports ports of localhost" do
    eh = EmbeddableHost.new(host: 'localhost:8080')
    expect(eh).to be_valid
    expect(eh.host).to eq('localhost:8080')
  end

  it "supports ports for ip addresses" do
    eh = EmbeddableHost.new(host: '192.168.0.1:3000')
    expect(eh).to be_valid
    expect(eh.host).to eq('192.168.0.1:3000')
  end

  it "supports subdomains of localhost" do
    eh = EmbeddableHost.new(host: 'discourse.localhost')
    expect(eh).to be_valid
    expect(eh.host).to eq('discourse.localhost')
  end

  it "rejects misspellings of localhost" do
    eh = EmbeddableHost.new(host: 'alocalhost')
    expect(eh).not_to be_valid
  end

  describe "it works with ports" do
    let!(:host) { Fabricate(:embeddable_host, host: 'localhost:8000') }

    it "works as expected" do
      expect(EmbeddableHost.url_allowed?('http://localhost:8000/eviltrout')).to eq(true)
    end
  end

  describe "url_allowed?" do
    let!(:host) { Fabricate(:embeddable_host) }

    it 'works as expected' do
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com')).to eq(true)
      expect(EmbeddableHost.url_allowed?('https://eviltrout.com')).to eq(true)
      expect(EmbeddableHost.url_allowed?('https://eviltrout.com/انگلیسی')).to eq(true)
      expect(EmbeddableHost.url_allowed?('https://not-eviltrout.com')).to eq(false)
    end

    it 'works with multiple hosts' do
      Fabricate(:embeddable_host, host: 'discourse.org')
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com')).to eq(true)
      expect(EmbeddableHost.url_allowed?('http://discourse.org')).to eq(true)
    end

    it 'always allow forum own URL' do
      expect(EmbeddableHost.url_allowed?(Discourse.base_url)).to eq(true)
    end
  end

  describe "path_whitelist" do
    it "matches the path" do
      Fabricate(:embeddable_host, path_whitelist: '^/fp/\d{4}/\d{2}/\d{2}/.*$')
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com')).to eq(false)
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/fp/2016/08/25/test-page')).to eq(true)
    end

    it "respects query parameters" do
      Fabricate(:embeddable_host, path_whitelist: '^/fp$')
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/fp?test=1')).to eq(false)
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/fp')).to eq(true)
    end

    it "allows multiple records with different paths" do
      Fabricate(:embeddable_host, path_whitelist: '/rick/.*')
      Fabricate(:embeddable_host, path_whitelist: '/morty/.*')
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/rick/smith')).to eq(true)
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/morty/sanchez')).to eq(true)
    end

    it "works with non-english paths" do
      Fabricate(:embeddable_host, path_whitelist: '/انگلیسی/.*')
      Fabricate(:embeddable_host, path_whitelist: '/definição/.*')
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/انگلیسی/foo')).to eq(true)
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/definição/foo')).to eq(true)
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/bar/foo')).to eq(false)
    end

    it "works with URL encoded paths" do
      Fabricate(:embeddable_host, path_whitelist: '/definição/.*')
      Fabricate(:embeddable_host, path_whitelist: '/ingl%C3%A9s/.*')

      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/defini%C3%A7%C3%A3o/foo')).to eq(true)
      expect(EmbeddableHost.url_allowed?('http://eviltrout.com/inglés/foo')).to eq(true)
    end
  end

end
