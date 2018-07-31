require 'rails_helper'
require 'topic_view'

describe TopicQuery do

  let!(:user) { Fabricate(:coding_horror) }
  let(:creator) { Fabricate(:user) }
  let(:topic_query) { TopicQuery.new(user) }

  let(:moderator) { Fabricate(:moderator) }
  let(:admin) { Fabricate(:admin) }

  context 'secure category' do
    it "filters categories out correctly" do
      category = Fabricate(:category)
      group = Fabricate(:group)
      category.set_permissions(group => :full)
      category.save

      Fabricate(:topic, category: category)
      Fabricate(:topic, visible: false)

      expect(TopicQuery.new(nil).list_latest.topics.count).to eq(0)
      expect(TopicQuery.new(user).list_latest.topics.count).to eq(0)

      expect(Topic.top_viewed(10).count).to eq(0)
      expect(Topic.recent(10).count).to eq(0)

      # mods can see hidden topics
      expect(TopicQuery.new(moderator).list_latest.topics.count).to eq(1)
      # admins can see all the topics
      expect(TopicQuery.new(admin).list_latest.topics.count).to eq(3)

      group.add(user)
      group.save

      expect(TopicQuery.new(user).list_latest.topics.count).to eq(2)

    end

  end

  context "custom filters" do
    it "allows custom filters to be applied" do
      topic1 = Fabricate(:topic)
      _topic2 = Fabricate(:topic)

      TopicQuery.add_custom_filter(:only_topic_id) do |results, topic_query|
        results = results.where('topics.id = ?', topic_query.options[:only_topic_id])
      end

      expect(TopicQuery.new(nil, only_topic_id: topic1.id).list_latest.topics.map(&:id)).to eq([topic1.id])

      TopicQuery.remove_custom_filter(:only_topic_id)
    end
  end

  context "list_topics_by" do

    it "allows users to view their own invisible topics" do
      _topic = Fabricate(:topic, user: user)
      _invisible_topic = Fabricate(:topic, user: user, visible: false)

      expect(TopicQuery.new(nil).list_topics_by(user).topics.count).to eq(1)
      expect(TopicQuery.new(user).list_topics_by(user).topics.count).to eq(2)
    end

  end

  context "prioritize_pinned_topics" do

    it "does the pagination correctly" do

      num_topics = 15
      per_page = 3

      topics = []
      (num_topics - 1).downto(0).each do |i|
        topics[i] = Fabricate(:topic)
      end

      topic_query = TopicQuery.new(user)
      results = topic_query.send(:default_results)

      expect(topic_query.prioritize_pinned_topics(results,
        per_page: per_page,
        page: 0)
      ).to eq(topics[0...per_page])

      expect(topic_query.prioritize_pinned_topics(results,
        per_page: per_page,
        page: 1)
      ).to eq(topics[per_page...num_topics])
    end

  end

  context 'bookmarks' do
    it "filters and returns bookmarks correctly" do
      post = Fabricate(:post)
      reply = Fabricate(:post, topic: post.topic)

      post2 = Fabricate(:post)

      PostAction.act(user, post, PostActionType.types[:bookmark])
      PostAction.act(user, reply, PostActionType.types[:bookmark])
      TopicUser.change(user, post.topic, notification_level: 1)
      TopicUser.change(user, post2.topic, notification_level: 1)

      query = TopicQuery.new(user, filter: 'bookmarked').list_latest

      expect(query.topics.length).to eq(1)
      expect(query.topics.first.user_data.post_action_data).to eq(PostActionType.types[:bookmark] => [1, 2])
    end
  end

  context 'deleted filter' do
    it "filters deleted topics correctly" do
      _topic = Fabricate(:topic, deleted_at: 1.year.ago)

      expect(TopicQuery.new(admin, status: 'deleted').list_latest.topics.size).to eq(1)
      expect(TopicQuery.new(moderator, status: 'deleted').list_latest.topics.size).to eq(1)
      expect(TopicQuery.new(user, status: 'deleted').list_latest.topics.size).to eq(0)
      expect(TopicQuery.new(nil, status: 'deleted').list_latest.topics.size).to eq(0)
    end
  end

  context 'category filter' do
    let(:category) { Fabricate(:category) }

    let(:diff_category) { Fabricate(:diff_category) }

    it "returns topics in the category when we filter to it" do
      expect(TopicQuery.new(moderator).list_latest.topics.size).to eq(0)

      # Filter by slug
      expect(TopicQuery.new(moderator, category: category.slug).list_latest.topics.size).to eq(1)
      expect(TopicQuery.new(moderator, category: "#{category.id}-category").list_latest.topics.size).to eq(1)

      list = TopicQuery.new(moderator, category: diff_category.slug).list_latest
      expect(list.topics.size).to eq(1)
      expect(list.preload_key).to eq("topic_list_c/different-category/l/latest")

      # Defaults to no category filter when slug does not exist
      expect(TopicQuery.new(moderator, category: 'made up slug').list_latest.topics.size).to eq(2)
    end

    context 'subcategories' do
      let!(:subcategory) { Fabricate(:category, parent_category_id: category.id) }

      it "works with subcategories" do
        expect(TopicQuery.new(moderator, category: category.id).list_latest.topics.size).to eq(1)
        expect(TopicQuery.new(moderator, category: subcategory.id).list_latest.topics.size).to eq(1)
        expect(TopicQuery.new(moderator, category: category.id, no_subcategories: true).list_latest.topics.size).to eq(1)
      end

    end
  end

  context 'tag filter' do
    let(:tag)       { Fabricate(:tag) }
    let(:other_tag) { Fabricate(:tag) }

    before do
      SiteSetting.tagging_enabled = true
    end

    context "no category filter" do
      let!(:tagged_topic1) { Fabricate(:topic, tags: [tag]) }
      let!(:tagged_topic2) { Fabricate(:topic, tags: [other_tag]) }
      let!(:tagged_topic3) { Fabricate(:topic, tags: [tag, other_tag]) }
      let!(:no_tags_topic) { Fabricate(:topic) }

      it "returns topics with the tag when filtered to it" do
        expect(TopicQuery.new(moderator, tags: [tag.name]).list_latest.topics)
          .to contain_exactly(tagged_topic1, tagged_topic3)

        expect(TopicQuery.new(moderator, tags: [tag.id]).list_latest.topics)
          .to contain_exactly(tagged_topic1, tagged_topic3)

        expect(TopicQuery.new(
          moderator, tags: [tag.name, other_tag.name]
        ).list_latest.topics).to contain_exactly(
          tagged_topic1, tagged_topic2, tagged_topic3
        )

        expect(TopicQuery.new(moderator, tags: [tag.id, other_tag.id]).list_latest.topics)
          .to contain_exactly(tagged_topic1, tagged_topic2, tagged_topic3)
      end

      it "can return topics with all specified tags" do
        expect(TopicQuery.new(moderator, tags: [tag.name, other_tag.name], match_all_tags: true).list_latest.topics.map(&:id)).to eq([tagged_topic3.id])
      end

      it "returns an empty relation when an invalid tag is passed" do
        expect(TopicQuery.new(moderator, tags: [tag.name, 'notatag'], match_all_tags: true).list_latest.topics).to be_empty
      end

      it "can return topics with no tags" do
        expect(TopicQuery.new(moderator, no_tags: true).list_latest.topics.map(&:id)).to eq([no_tags_topic.id])
      end
    end

    context "and categories too" do
      let(:category1) { Fabricate(:category) }
      let(:category2) { Fabricate(:category) }

      it "returns topics in the given category with the given tag" do
        tagged_topic1 = Fabricate(:topic, category: category1, tags: [tag])
        tagged_topic2 = Fabricate(:topic, category: category2, tags: [tag])
        tagged_topic3 = Fabricate(:topic, category: category1, tags: [tag, other_tag])
        no_tags_topic = Fabricate(:topic, category: category1)

        expect(TopicQuery.new(moderator, category: category1.id, tags: [tag.name]).list_latest.topics.map(&:id).sort).to eq([tagged_topic1.id, tagged_topic3.id].sort)
        expect(TopicQuery.new(moderator, category: category2.id, tags: [other_tag.name]).list_latest.topics.size).to eq(0)
      end
    end
  end

  context 'muted categories' do
    it 'is removed from new and latest lists' do
      category = Fabricate(:category)
      topic = Fabricate(:topic, category: category)
      CategoryUser.create!(user_id: user.id,
                           category_id: category.id,
                           notification_level: CategoryUser.notification_levels[:muted])
      expect(topic_query.list_new.topics.map(&:id)).not_to include(topic.id)
      expect(topic_query.list_latest.topics.map(&:id)).not_to include(topic.id)
    end
  end

  context 'muted tags' do
    it 'is removed from new and latest lists' do
      SiteSetting.tagging_enabled = true
      SiteSetting.remove_muted_tags_from_latest = true

      muted_tag, other_tag = Fabricate(:tag), Fabricate(:tag)

      muted_topic = Fabricate(:topic, tags: [muted_tag])
      tagged_topic = Fabricate(:topic, tags: [other_tag])
      untagged_topic = Fabricate(:topic)

      TagUser.create!(user_id: user.id,
                      tag_id: muted_tag.id,
                      notification_level: CategoryUser.notification_levels[:muted])

      topic_ids = topic_query.list_latest.topics.map(&:id)
      expect(topic_ids).not_to include(muted_topic.id)
      expect(topic_ids).to include(tagged_topic.id)
      expect(topic_ids).to include(untagged_topic.id)

      topic_ids = topic_query.list_new.topics.map(&:id)
      expect(topic_ids).not_to include(muted_topic.id)
      expect(topic_ids).to include(tagged_topic.id)
      expect(topic_ids).to include(untagged_topic.id)
    end
  end

  context 'a bunch of topics' do
    let!(:regular_topic) do
      Fabricate(:topic, title: 'this is a regular topic',
                        user: creator,
                        views: 100,
                        like_count: 66,
                        posts_count: 3,
                        participant_count: 11,
                        bumped_at: 15.minutes.ago)
    end
    let!(:pinned_topic) do
      Fabricate(:topic, title: 'this is a pinned topic',
                        user: creator,
                        views: 10,
                        like_count: 100,
                        posts_count: 5,
                        participant_count: 12,
                        pinned_at: 10.minutes.ago,
                        pinned_globally: true,
                        bumped_at: 10.minutes.ago)
    end
    let!(:archived_topic) do
      Fabricate(:topic, title: 'this is an archived topic',
                        user: creator,
                        views: 50,
                        like_count: 30,
                        posts_count: 4,
                        archived: true,
                        participant_count: 1,
                        bumped_at: 6.minutes.ago)
    end
    let!(:invisible_topic) do
      Fabricate(:topic, title: 'this is an invisible topic',
                        user: creator,
                        views: 1,
                        like_count: 5,
                        posts_count: 2,
                        visible: false,
                        participant_count: 3,
                        bumped_at: 5.minutes.ago)
    end
    let!(:closed_topic) do
      Fabricate(:topic, title: 'this is a closed topic',
                        user: creator,
                        views: 2,
                        like_count: 1,
                        posts_count: 1,
                        closed: true,
                        participant_count: 2,
                        bumped_at: 1.minute.ago)
    end
    let!(:future_topic) do
      Fabricate(:topic, title: 'this is a topic in far future',
                        user: creator,
                        views: 30,
                        like_count: 11,
                        posts_count: 6,
                        participant_count: 5,
                        bumped_at: 1000.years.from_now)
    end

    let(:topics) { topic_query.list_latest.topics }

    context 'list_latest' do
      it "returns the topics in the correct order" do
        expect(topics.map(&:id)).to eq([pinned_topic, future_topic, closed_topic, archived_topic, regular_topic].map(&:id))

        # includes the invisible topic if you're a moderator
        expect(TopicQuery.new(moderator).list_latest.topics.include?(invisible_topic)).to eq(true)

        # includes the invisible topic if you're an admin" do
        expect(TopicQuery.new(admin).list_latest.topics.include?(invisible_topic)).to eq(true)
      end

      context 'sort_order' do

        def ids_in_order(order, descending = true)
          TopicQuery.new(admin, order: order, ascending: descending ? 'false' : 'true').list_latest.topics.map(&:id)
        end

        it "returns the topics in correct order" do
          # returns the topics in likes order if requested
          expect(ids_in_order('posts')).to eq([future_topic, pinned_topic, archived_topic, regular_topic, invisible_topic, closed_topic].map(&:id))

          # returns the topics in reverse likes order if requested
          expect(ids_in_order('posts', false)).to eq([closed_topic, invisible_topic, regular_topic, archived_topic, pinned_topic, future_topic].map(&:id))

          # returns the topics in likes order if requested
          expect(ids_in_order('likes')).to eq([pinned_topic, regular_topic, archived_topic, future_topic, invisible_topic, closed_topic].map(&:id))

          # returns the topics in reverse likes order if requested
          expect(ids_in_order('likes', false)).to eq([closed_topic, invisible_topic, future_topic, archived_topic, regular_topic, pinned_topic].map(&:id))

          # returns the topics in views order if requested
          expect(ids_in_order('views')).to eq([regular_topic, archived_topic, future_topic, pinned_topic, closed_topic, invisible_topic].map(&:id))

          # returns the topics in reverse views order if requested" do
          expect(ids_in_order('views', false)).to eq([invisible_topic, closed_topic, pinned_topic, future_topic, archived_topic, regular_topic].map(&:id))

          # returns the topics in posters order if requested" do
          expect(ids_in_order('posters')).to eq([pinned_topic, regular_topic, future_topic, invisible_topic, closed_topic, archived_topic].map(&:id))

          # returns the topics in reverse posters order if requested" do
          expect(ids_in_order('posters', false)).to eq([archived_topic, closed_topic, invisible_topic, future_topic, regular_topic, pinned_topic].map(&:id))

          # sets a custom field for each topic to emulate a plugin
          regular_topic.custom_fields["sheep"] = 26
          pinned_topic.custom_fields["sheep"] = 47
          archived_topic.custom_fields["sheep"] = 69
          invisible_topic.custom_fields["sheep"] = 12
          closed_topic.custom_fields["sheep"] = 31
          future_topic.custom_fields["sheep"] = 53

          regular_topic.save
          pinned_topic.save
          archived_topic.save
          invisible_topic.save
          closed_topic.save
          future_topic.save

          # adds the custom field as a viable sort option
          class ::TopicQuery
            SORTABLE_MAPPING["sheep"] = "custom_fields.sheep"
          end
          # returns the topics in the sheep order if requested" do
          expect(ids_in_order('sheep')).to eq([archived_topic, future_topic, pinned_topic, closed_topic, regular_topic, invisible_topic].map(&:id))

          # returns the topics in reverse sheep order if requested" do
          expect(ids_in_order('sheep', false)).to eq([invisible_topic, regular_topic, closed_topic, pinned_topic, future_topic, archived_topic].map(&:id))

        end

      end

    end

    context 'after clearring a pinned topic' do
      before do
        pinned_topic.clear_pin_for(user)
      end

      it "no longer shows the pinned topic at the top" do
        expect(topics).to eq([future_topic, closed_topic, archived_topic, pinned_topic, regular_topic])
      end
    end

  end

  context 'categorized' do
    let(:category) { Fabricate(:category) }
    let(:topic_category) { category.topic }
    let!(:topic_no_cat) { Fabricate(:topic) }
    let!(:topic_in_cat1) { Fabricate(:topic, category: category,
                                             bumped_at: 10.minutes.ago,
                                             created_at: 10.minutes.ago) }
    let!(:topic_in_cat2) { Fabricate(:topic, category: category) }

    describe '#list_new_in_category' do
      it 'returns the topic category and the categorized topic' do
        expect(
          topic_query.list_new_in_category(category).topics.map(&:id)
        ).to eq([topic_in_cat2.id, topic_category.id, topic_in_cat1.id])
      end
    end

    describe "category default sort order" do
      it "can use category's default sort order" do
        category.update_attributes!(sort_order: 'created', sort_ascending: true)
        topic_ids = TopicQuery.new(user, category: category.id).list_latest.topics.map(&:id)
        expect(topic_ids - [topic_category.id]).to eq([topic_in_cat1.id, topic_in_cat2.id])
      end

      it "ignores invalid order value" do
        category.update_attributes!(sort_order: 'funny')
        topic_ids = TopicQuery.new(user, category: category.id).list_latest.topics.map(&:id)
        expect(topic_ids - [topic_category.id]).to eq([topic_in_cat2.id, topic_in_cat1.id])
      end

      it "can be overridden" do
        category.update_attributes!(sort_order: 'created', sort_ascending: true)
        topic_ids = TopicQuery.new(user, category: category.id, order: 'activity').list_latest.topics.map(&:id)
        expect(topic_ids - [topic_category.id]).to eq([topic_in_cat2.id, topic_in_cat1.id])
      end
    end
  end

  context 'unread / read topics' do

    context 'with no data' do
      it "has no unread topics" do
        expect(topic_query.list_unread.topics).to be_blank
      end
    end

    context 'with whispers' do

      it 'correctly shows up in unread for staff' do

        first = create_post(raw: 'this is the first post', title: 'super amazing title')

        _whisper = create_post(topic_id: first.topic.id,
                               post_type: Post.types[:whisper],
                               raw: 'this is a whispered reply')

        topic_id = first.topic.id

        TopicUser.update_last_read(user, topic_id, first.post_number, 1, 1)
        TopicUser.update_last_read(admin, topic_id, first.post_number, 1, 1)

        TopicUser.change(user.id, topic_id, notification_level: TopicUser.notification_levels[:tracking])
        TopicUser.change(admin.id, topic_id, notification_level: TopicUser.notification_levels[:tracking])

        expect(TopicQuery.new(user).list_unread.topics).to eq([])
        expect(TopicQuery.new(admin).list_unread.topics).to eq([first.topic])
      end
    end

    context 'with read data' do
      let!(:partially_read) { Fabricate(:post, user: creator).topic }
      let!(:fully_read) { Fabricate(:post, user: creator).topic }

      before do
        TopicUser.update_last_read(user, partially_read.id, 0, 0, 0)
        TopicUser.update_last_read(user, fully_read.id, 1, 1, 0)
      end

      context 'list_unread' do
        it 'lists topics correctly' do
          new_topic = Fabricate(:post, user: creator).topic

          expect(topic_query.list_unread.topics).to eq([])
          expect(topic_query.list_read.topics).to match_array([fully_read, partially_read])
        end
      end

      context 'user with auto_track_topics list_unread' do
        before do
          user.user_option.auto_track_topics_after_msecs = 0
          user.user_option.save
        end

        it 'only contains the partially read topic' do
          expect(topic_query.list_unread.topics).to eq([partially_read])
        end
      end

    end

  end

  context 'list_new' do

    context 'without a new topic' do
      it "has no new topics" do
        expect(topic_query.list_new.topics).to be_blank
      end
    end

    context 'preload api' do
      let(:topics) {}

      it "preloads data correctly" do
        TopicList.preloaded_custom_fields << "tag"
        TopicList.preloaded_custom_fields << "age"
        TopicList.preloaded_custom_fields << "foo"

        topic = Fabricate.build(:topic, user: creator, bumped_at: 10.minutes.ago)
        topic.custom_fields["tag"] = ["a", "b", "c"]
        topic.custom_fields["age"] = 22
        topic.save

        new_topic = topic_query.list_new.topics.first

        expect(new_topic.custom_fields["tag"].sort).to eq(["a", "b", "c"])
        expect(new_topic.custom_fields["age"]).to eq("22")

        expect(new_topic.custom_field_preloaded?("tag")).to eq(true)
        expect(new_topic.custom_field_preloaded?("age")).to eq(true)
        expect(new_topic.custom_field_preloaded?("foo")).to eq(true)
        expect(new_topic.custom_field_preloaded?("bar")).to eq(false)

        TopicList.preloaded_custom_fields.clear

        # if we attempt to access non preloaded fields explode
        expect { new_topic.custom_fields["boom"] }.to raise_error(StandardError)

      end
    end

    context 'with a new topic' do
      let!(:new_topic) { Fabricate(:topic, user: creator, bumped_at: 10.minutes.ago) }
      let(:topics) { topic_query.list_new.topics }

      it "contains no new topics for a user that has missed the window" do

        expect(topic_query.list_new.topics).to eq([new_topic])

        user.user_option.new_topic_duration_minutes = 5
        user.user_option.save
        new_topic.created_at = 10.minutes.ago
        new_topic.save
        expect(topic_query.list_new.topics).to eq([])
      end

      context "muted topics" do
        before do
          new_topic.notify_muted!(user)
        end

        it "returns an empty set" do
          expect(topics).to be_blank
          expect(topic_query.list_latest.topics).to be_blank
        end

        context 'un-muted' do
          before do
            new_topic.notify_tracking!(user)
          end

          it "returns the topic again" do
            expect(topics).to eq([new_topic])
            expect(topic_query.list_latest.topics).not_to be_blank
          end
        end
      end
    end

  end

  context 'list_posted' do
    let(:topics) { topic_query.list_posted.topics }

    it "returns blank when there are no posted topics" do
      expect(topics).to be_blank
    end

    context 'created topics' do
      let!(:created_topic) { create_post(user: user).topic }

      it "includes the created topic" do
        expect(topics.include?(created_topic)).to eq(true)
      end
    end

    context "topic you've posted in" do
      let(:other_users_topic) { create_post(user: creator).topic }
      let!(:your_post) { create_post(user: user, topic: other_users_topic) }

      it "includes the posted topic" do
        expect(topics.include?(other_users_topic)).to eq(true)
      end
    end

    context "topic you haven't posted in" do
      let(:other_users_topic) { create_post(user: creator).topic }

      it "does not include the topic" do
        expect(topics).to be_blank
      end

      context "but interacted with" do

        it "is not included if read" do
          TopicUser.update_last_read(user, other_users_topic.id, 0, 0, 0)

          expect(topics).to be_blank
        end

        it "is not included if muted" do
          other_users_topic.notify_muted!(user)

          expect(topics).to be_blank
        end

        it "is not included if tracking" do
          other_users_topic.notify_tracking!(user)

          expect(topics).to be_blank
        end
      end
    end
  end

  context 'suggested_for message do' do

    let(:user) do
      Fabricate(:admin)
    end

    let(:sender) do
      Fabricate(:admin)
    end

    let(:group_with_user) do
      group = Fabricate(:group)
      group.add(user)
      group.save
      group
    end

    def create_pm(user, opts = nil)
      unless opts
        opts = user
        user = nil
      end

      create_post(opts.merge(user: user, archetype: Archetype.private_message)).topic
    end

    def read(user, topic, post_number)
      TopicUser.update_last_read(user, topic, post_number, post_number, 10000)
    end

    it 'returns the correct suggestions' do

      pm_to_group = create_pm(sender, target_group_names: [group_with_user.name])
      pm_to_user = create_pm(sender, target_usernames: [user.username])

      new_pm = create_pm(target_usernames: [user.username])

      unread_pm = create_pm(target_usernames: [user.username])
      read(user, unread_pm, 0)

      old_unrelated_pm = create_pm(target_usernames: [user.username])
      read(user, old_unrelated_pm, 1)

      related_by_user_pm = create_pm(sender, target_usernames: [user.username])
      read(user, related_by_user_pm, 1)

      related_by_group_pm = create_pm(sender, target_group_names: [group_with_user.name])
      read(user, related_by_group_pm, 1)

      expect(TopicQuery.new(user).list_suggested_for(pm_to_group).topics.map(&:id)).to(
        eq([related_by_group_pm.id, related_by_user_pm.id, pm_to_user.id])
      )

      expect(TopicQuery.new(user).list_suggested_for(pm_to_user).topics.map(&:id)).to(
        eq([new_pm.id, unread_pm.id, related_by_user_pm.id])
      )

      SiteSetting.enable_personal_messages = false
      expect(TopicQuery.new(user).list_suggested_for(pm_to_group)).to be_blank
      expect(TopicQuery.new(user).list_suggested_for(pm_to_user)).to be_blank
    end
  end

  context 'suggested_for' do
    def clear_cache!
      $redis.keys('random_topic_cache*').each { |k| $redis.del k }
    end

    before do
      clear_cache!
    end

    context 'when anonymous' do
      let(:topic) { Fabricate(:topic) }
      let!(:new_topic) { Fabricate(:post, user: creator).topic }

      it "should return the new topic" do
        expect(TopicQuery.new.list_suggested_for(topic).topics).to eq([new_topic])
      end
    end

    context "anonymously browsing with invisible, closed and archived" do
      let!(:topic) { Fabricate(:topic) }
      let!(:regular_topic) { Fabricate(:post, user: creator).topic }
      let!(:closed_topic) { Fabricate(:topic, user: creator, closed: true) }
      let!(:archived_topic) { Fabricate(:topic, user: creator, archived: true) }
      let!(:invisible_topic) { Fabricate(:topic, user: creator, visible: false) }

      it "should omit the closed/archived/invisbiel topics from suggested" do
        expect(TopicQuery.new.list_suggested_for(topic).topics).to eq([regular_topic])
      end
    end

    context 'when logged in' do

      let(:topic) { Fabricate(:topic) }
      let(:suggested_topics) {
        tt = topic
        # lets clear cache once category is created - working around caching is hard
        clear_cache!
        topic_query.list_suggested_for(tt).topics.map { |t| t.id }
      }

      it "should return empty results when there is nothing to find" do
        expect(suggested_topics).to be_blank
      end

      context 'random suggested' do

        let!(:new_topic) { Fabricate(:topic, created_at: 2.days.ago) }
        let!(:old_topic) { Fabricate(:topic, created_at: 3.years.ago) }

        it 'respects suggested_topics_max_days_old' do
          SiteSetting.suggested_topics_max_days_old = 1365
          tt = topic

          clear_cache!
          expect(topic_query.list_suggested_for(tt).topics.length).to eq(2)

          SiteSetting.suggested_topics_max_days_old = 365
          clear_cache!

          expect(topic_query.list_suggested_for(tt).topics.length).to eq(1)
        end

      end

      context 'with private messages' do
        let(:group_user) { Fabricate(:user) }
        let(:group) { Fabricate(:group) }
        let(:another_group) { Fabricate(:group) }

        let!(:topic) do
          Fabricate(:private_message_topic,
            topic_allowed_users: [
              Fabricate.build(:topic_allowed_user, user: user)
            ],
            topic_allowed_groups: [
              Fabricate.build(:topic_allowed_group, group: group)
            ]
          )
        end

        let!(:private_message) do
          Fabricate(:private_message_topic,
            topic_allowed_users: [
              Fabricate.build(:topic_allowed_user, user: user)
            ],
            topic_allowed_groups: [
              Fabricate.build(:topic_allowed_group, group: group),
              Fabricate.build(:topic_allowed_group, group: another_group),
            ]
          )
        end

        let!(:private_group_topic) do
          Fabricate(:private_message_topic,
            user: Fabricate(:user),
            topic_allowed_groups: [
              Fabricate.build(:topic_allowed_group, group: group)
            ]
          )
        end

        before do
          group.add(group_user)
          another_group.add(user)
        end

        describe 'as user not part of group' do
          let!(:user) { Fabricate(:user) }

          it 'should not return topics by the group user' do
            expect(suggested_topics).to eq([private_message.id])
          end
        end

        describe 'as user part of group' do
          let!(:user) { group_user }

          it 'should return the group topics' do
            expect(suggested_topics).to eq([private_group_topic.id, private_message.id])
          end
        end

        context "by tag filter" do
          let(:tag) { Fabricate(:tag) }
          let!(:user) { group_user }

          it 'should return only tagged topics' do
            Fabricate(:topic_tag, topic: private_message, tag: tag)
            Fabricate(:topic_tag, topic: private_group_topic)

            expect(TopicQuery.new(user, tags: [tag.name]).list_private_messages_tag(user).topics).to eq([private_message])
          end

        end
      end

      context 'with some existing topics' do
        let!(:partially_read) { Fabricate(:post, user: creator).topic }
        let!(:new_topic) { Fabricate(:post, user: creator).topic }
        let!(:fully_read) { Fabricate(:post, user: creator).topic }
        let!(:closed_topic) { Fabricate(:topic, user: creator, closed: true) }
        let!(:archived_topic) { Fabricate(:topic, user: creator, archived: true) }
        let!(:invisible_topic) { Fabricate(:topic, user: creator, visible: false) }
        let!(:fully_read_closed) { Fabricate(:post, user: creator).topic }
        let!(:fully_read_archived) { Fabricate(:post, user: creator).topic }

        before do
          user.user_option.auto_track_topics_after_msecs = 0
          user.user_option.save
          TopicUser.update_last_read(user, partially_read.id, 0, 0, 0)
          TopicUser.update_last_read(user, fully_read.id, 1, 1, 0)
          TopicUser.update_last_read(user, fully_read_closed.id, 1, 1, 0)
          TopicUser.update_last_read(user, fully_read_archived.id, 1, 1, 0)
          fully_read_closed.closed = true
          fully_read_closed.save
          fully_read_archived.archived = true
          fully_read_archived.save
        end

        it "returns unread, then new, then random" do
          SiteSetting.suggested_topics = 7
          expect(suggested_topics[0]).to eq(partially_read.id)
          expect(suggested_topics[1, 3]).to include(new_topic.id)
          expect(suggested_topics[1, 3]).to include(closed_topic.id)
          expect(suggested_topics[1, 3]).to include(archived_topic.id)

          # The line below appears to randomly fail, no idea why need to restructure test
          #expect(suggested_topics[4]).to eq(fully_read.id)
          # random doesn't include closed and archived
        end

        it "won't return new or fully read if there are enough partially read topics" do
          SiteSetting.suggested_topics = 1
          expect(suggested_topics).to eq([partially_read.id])
        end

        it "won't return fully read if there are enough partially read topics and new topics" do
          SiteSetting.suggested_topics = 4
          expect(suggested_topics[0]).to eq(partially_read.id)
          expect(suggested_topics[1, 3]).to include(new_topic.id)
          expect(suggested_topics[1, 3]).to include(closed_topic.id)
          expect(suggested_topics[1, 3]).to include(archived_topic.id)
        end
      end
    end
  end

  describe '#list_group_topics' do
    let(:group) { Fabricate(:group) }

    let(:user) do
      user = Fabricate(:user)
      group.add(user)
      user
    end

    let(:user2) do
      user = Fabricate(:user)
      group.add(user)
      user
    end

    let(:user3) { Fabricate(:user) }

    let(:private_category) do
      Fabricate(:private_category, group: group)
    end

    let!(:private_message_topic) { Fabricate(:private_message_post, user: user).topic }
    let!(:topic1) { Fabricate(:topic, user: user) }
    let!(:topic2) { Fabricate(:topic, user: user, category: Fabricate(:category)) }
    let!(:topic3) { Fabricate(:topic, user: user, category: private_category) }
    let!(:topic4) { Fabricate(:topic) }
    let!(:topic5) { Fabricate(:topic, user: user, visible: false) }
    let!(:topic6) { Fabricate(:topic, user: user2) }

    it 'should return the right lists for anon user' do
      topics = TopicQuery.new.list_group_topics(group).topics

      expect(topics).to contain_exactly(topic1, topic2, topic6)
    end

    it 'should retun the right list for users in the same group' do
      topics = TopicQuery.new(user).list_group_topics(group).topics

      expect(topics).to contain_exactly(topic1, topic2, topic3, topic6)

      topics = TopicQuery.new(user2).list_group_topics(group).topics

      expect(topics).to contain_exactly(topic1, topic2, topic3, topic6)
    end

    it 'should return the right list for user no in the group' do
      topics = TopicQuery.new(user3).list_group_topics(group).topics

      expect(topics).to contain_exactly(topic1, topic2, topic6)
    end
  end

  describe '#list_private_messages_group' do
    let(:group) { Fabricate(:group) }

    let!(:group_message) do
      Fabricate(:private_message_topic,
        allowed_groups: [group],
        topic_allowed_users: [
          Fabricate.build(:topic_allowed_user, user: Fabricate(:user)),
        ]
      )
    end

    before do
      group.add(creator)
    end

    it 'should return the right list for a group user' do
      topics = TopicQuery.new(nil, group_name: group.name)
        .list_private_messages_group(creator)
        .topics

      expect(topics).to contain_exactly(group_message)
    end

    it 'should return the right list for an admin not part of the group' do
      topics = TopicQuery.new(nil, group_name: group.name)
        .list_private_messages_group(Fabricate(:admin))
        .topics

      expect(topics).to contain_exactly(group_message)
    end

    it 'should return the right list for a user not part of the group' do
      topics = TopicQuery.new(nil, group_name: group.name)
        .list_private_messages_group(Fabricate(:user))
        .topics

      expect(topics).to eq([])
    end
  end

  context "shared drafts" do
    let(:category) { Fabricate(:category) }
    let(:shared_drafts_category) { Fabricate(:category) }
    let!(:topic) { Fabricate(:topic, category: shared_drafts_category) }
    let!(:shared_draft) { Fabricate(:shared_draft, topic: topic, category: category) }
    let(:admin) { Fabricate(:admin) }
    let(:user) { Fabricate(:user) }
    let(:group) { Fabricate(:group) }

    before do
      shared_drafts_category.set_permissions(group => :full)
      shared_drafts_category.save
      SiteSetting.shared_drafts_category = shared_drafts_category.id
    end

    context "destination_category_id" do
      it "doesn't allow regular users to query destination_category_id" do
        list = TopicQuery.new(user, destination_category_id: category.id).list_latest
        expect(list.topics).not_to include(topic)
      end

      it "allows staff users to query destination_category_id" do
        list = TopicQuery.new(admin, destination_category_id: category.id).list_latest
        expect(list.topics).to include(topic)
      end
    end

    context "latest" do
      it "doesn't include shared topics unless filtering by category" do
        list = TopicQuery.new(moderator).list_latest
        expect(list.topics).not_to include(topic)
      end
    end
  end
end
