require_dependency 'url_helper'
require_dependency 'file_helper'

module ImportScripts
  class Uploader
    include ActionView::Helpers::NumberHelper

    # Creates an upload.
    # Expects path to be the full path and filename of the source file.
    # @return [Upload]
    def create_upload(user_id, path, source_filename)
      tmp = copy_to_tempfile(path)

      UploadCreator.new(tmp, source_filename).create_for(user_id)
    rescue => e
      puts "Failed to create upload: #{e}"
      nil
    ensure
      tmp.close rescue nil
      tmp.unlink rescue nil
    end

    def create_avatar(user, avatar_path)
      tempfile = copy_to_tempfile(avatar_path)
      filename = "avatar#{File.extname(avatar_path)}"
      upload = UploadCreator.new(tempfile, filename, type: "avatar").create_for(user.id)

      if upload.present? && upload.persisted?
        user.create_user_avatar
        user.user_avatar.update(custom_upload_id: upload.id)
        user.update(uploaded_avatar_id: upload.id)
      else
        puts "Failed to upload avatar for user #{user.username}: #{avatar_path}"
        puts upload.errors.inspect if upload
      end
    ensure
      tempfile.close! if tempfile
    end

    def html_for_upload(upload, display_filename)
      if FileHelper.is_image?(upload.url)
        embedded_image_html(upload)
      else
        attachment_html(upload, display_filename)
      end
    end

    def embedded_image_html(upload)
      image_width = [upload.width, SiteSetting.max_image_width].compact.min
      image_height = [upload.height, SiteSetting.max_image_height].compact.min
      upload_name = upload.short_url || upload.url
      %Q~![#{upload.original_filename}|#{image_width}x#{image_height}](#{upload_name})~
    end

    def attachment_html(upload, display_filename)
      "<a class='attachment' href='#{upload.url}'>#{display_filename}</a> (#{number_to_human_size(upload.filesize)})"
    end

    private

    def copy_to_tempfile(source_path)
      tmp = Tempfile.new('discourse-upload')

      File.open(source_path) do |source_stream|
        IO.copy_stream(source_stream, tmp)
      end

      tmp.rewind
      tmp
    end
  end
end
