require 'rails_helper'

describe NewPostManager do
  let(:user) { Fabricate(:newuser) }
  let(:admin) { Fabricate(:admin) }

  describe 'when new post containing a poll is queued for approval' do
    before do
      SiteSetting.poll_minimum_trust_level_to_create = 0
    end

    it 'should render the poll upon approval' do
      params = {
        raw: "[poll]\n* 1\n* 2\n* 3\n[/poll]",
        archetype: "regular",
        category: "",
        typing_duration_msecs: "2700",
        composer_open_duration_msecs: "12556",
        visible: true,
        image_sizes: nil,
        is_warning: false,
        title: "This is a test post with a poll",
        ip_address: "127.0.0.1",
        user_agent: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.87 Safari/537.36",
        referrer: "http://localhost:3000/",
        first_post_checks: true
      }

      expect { NewPostManager.new(user, params).perform }
        .to change { QueuedPost.count }.by(1)

      queued_post = QueuedPost.last
      queued_post.approve!(admin)

      expect(Post.last.custom_fields[DiscoursePoll::POLLS_CUSTOM_FIELD])
        .to_not eq(nil)
    end
  end
end
