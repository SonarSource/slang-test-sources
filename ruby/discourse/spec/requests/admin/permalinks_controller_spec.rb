require 'rails_helper'

describe Admin::PermalinksController do

  it "is a subclass of AdminController" do
    expect(Admin::PermalinksController < Admin::AdminController).to eq(true)
  end

  let(:admin) { Fabricate(:admin) }

  before do
    sign_in(admin)
  end

  describe '#index' do
    it 'filters url' do
      Fabricate(:permalink, url: "/forum/23")
      Fabricate(:permalink, url: "/forum/98")
      Fabricate(:permalink, url: "/discuss/topic/45")
      Fabricate(:permalink, url: "/discuss/topic/76")

      get "/admin/permalinks.json", params: { filter: "topic" }

      expect(response.status).to eq(200)
      result = JSON.parse(response.body)
      expect(result.length).to eq(2)
    end

    it 'filters external url' do
      Fabricate(:permalink, external_url: "http://google.com")
      Fabricate(:permalink, external_url: "http://wikipedia.org")
      Fabricate(:permalink, external_url: "http://www.discourse.org")
      Fabricate(:permalink, external_url: "http://try.discourse.org")

      get "/admin/permalinks.json", params: { filter: "discourse" }

      expect(response.status).to eq(200)
      result = JSON.parse(response.body)
      expect(result.length).to eq(2)
    end

    it 'filters url and external url both' do
      Fabricate(:permalink, url: "/forum/23", external_url: "http://google.com")
      Fabricate(:permalink, url: "/discourse/98", external_url: "http://wikipedia.org")
      Fabricate(:permalink, url: "/discuss/topic/45", external_url: "http://discourse.org")
      Fabricate(:permalink, url: "/discuss/topic/76", external_url: "http://try.discourse.org")

      get "/admin/permalinks.json", params: { filter: "discourse" }

      expect(response.status).to eq(200)
      result = JSON.parse(response.body)
      expect(result.length).to eq(3)
    end
  end
end
