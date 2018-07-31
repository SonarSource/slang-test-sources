# frozen_string_literal: true

require "test_helper"
require "database/setup"

require "active_storage/previewer/video_previewer"

class ActiveStorage::Previewer::VideoPreviewerTest < ActiveSupport::TestCase
  setup do
    @blob = create_file_blob(filename: "video.mp4", content_type: "video/mp4")
  end

  test "previewing an MP4 video" do
    ActiveStorage::Previewer::VideoPreviewer.new(@blob).preview do |attachable|
      assert_equal "image/jpeg", attachable[:content_type]
      assert_equal "video.jpg", attachable[:filename]

      image = MiniMagick::Image.read(attachable[:io])
      assert_equal 640, image.width
      assert_equal 480, image.height
      assert_equal "image/jpeg", image.mime_type
    end
  end
end
