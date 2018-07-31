# frozen_string_literal: true

require "service/shared_service_tests"

class ActiveStorage::Service::DiskServiceTest < ActiveSupport::TestCase
  SERVICE = ActiveStorage::Service::DiskService.new(root: File.join(Dir.tmpdir, "active_storage"))

  include ActiveStorage::Service::SharedServiceTests

  test "url generation" do
    assert_match(/^https:\/\/example.com\/rails\/active_storage\/disk\/.*\/avatar\.png\?content_type=image%2Fpng&disposition=inline/,
      @service.url(@key, expires_in: 5.minutes, disposition: :inline, filename: ActiveStorage::Filename.new("avatar.png"), content_type: "image/png"))
  end

  test "headers_for_direct_upload generation" do
    assert_equal({ "Content-Type" => "application/json" }, @service.headers_for_direct_upload(@key, content_type: "application/json"))
  end
end
