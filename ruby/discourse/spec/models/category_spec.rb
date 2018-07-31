# encoding: utf-8

require 'rails_helper'
require_dependency 'post_creator'

describe Category do
  it { is_expected.to validate_presence_of :user_id }
  it { is_expected.to validate_presence_of :name }

  it 'validates uniqueness of name' do
    Fabricate(:category)
    is_expected.to validate_uniqueness_of(:name).scoped_to(:parent_category_id)
  end

  it 'validates uniqueness in case insensitive way' do
    Fabricate(:category, name: "Cats")
    cats = Fabricate.build(:category, name: "cats")
    expect(cats).to_not be_valid
    expect(cats.errors[:name]).to be_present
  end

  describe "resolve_permissions" do
    it "can determine read_restricted" do
      read_restricted, resolved = Category.resolve_permissions(everyone: :full)

      expect(read_restricted).to be false
      expect(resolved).to be_blank
    end
  end

  describe "permissions_params" do
    it "returns the right group names and permission type" do
      category = Fabricate(:category)
      group = Fabricate(:group)
      category_group = Fabricate(:category_group, category: category, group: group)
      expect(category.permissions_params).to eq("#{group.name}" => category_group.permission_type)
    end
  end

  describe "topic_create_allowed and post_create_allowed" do
    it "works" do

      # NOTE we also have the uncategorized category ... hence the increased count

      _default_category = Fabricate(:category)
      full_category = Fabricate(:category)
      can_post_category = Fabricate(:category)
      can_read_category = Fabricate(:category)

      user = Fabricate(:user)
      group = Fabricate(:group)
      group.add(user)
      group.save

      admin = Fabricate(:admin)

      full_category.set_permissions(group => :full)
      full_category.save

      can_post_category.set_permissions(group => :create_post)
      can_post_category.save

      can_read_category.set_permissions(group => :readonly)
      can_read_category.save

      guardian = Guardian.new(admin)
      expect(Category.topic_create_allowed(guardian).count).to be(5)
      expect(Category.post_create_allowed(guardian).count).to be(5)
      expect(Category.secured(guardian).count).to be(5)

      guardian = Guardian.new(user)
      expect(Category.secured(guardian).count).to be(5)
      expect(Category.post_create_allowed(guardian).count).to be(4)
      expect(Category.topic_create_allowed(guardian).count).to be(3) # explicitly allowed once, default allowed once

      expect(Category.scoped_to_permissions(nil, [:readonly]).count).to be(2)

      # everyone has special semantics, test it as well
      can_post_category.set_permissions(everyone: :create_post)
      can_post_category.save

      expect(Category.post_create_allowed(guardian).count).to be(4)

      # anonymous has permission to create no topics
      guardian = Guardian.new(nil)
      expect(Category.post_create_allowed(guardian).count).to be(0)
      expect(Category.topic_create_allowed(guardian).count).to be(0)
      expect(Category.scoped_to_permissions(guardian, [:readonly]).count).to be(3)

    end

  end

  describe "security" do
    let(:category) { Fabricate(:category) }
    let(:category_2) { Fabricate(:category) }
    let(:user) { Fabricate(:user) }
    let(:group) { Fabricate(:group) }

    it "secures categories correctly" do
      expect(category.read_restricted?).to be false

      category.set_permissions({})
      expect(category.read_restricted?).to be true

      category.set_permissions(everyone: :full)
      expect(category.read_restricted?).to be false

      expect(user.secure_categories).to be_empty

      group.add(user)
      group.save

      category.set_permissions(group.id => :full)
      category.save

      user.reload
      expect(user.secure_categories).to eq([category])
    end

    it "lists all secured categories correctly" do
      uncategorized = Category.find(SiteSetting.uncategorized_category_id)

      group.add(user)
      category.set_permissions(group.id => :full)
      category.save!
      category_2.set_permissions(group.id => :full)
      category_2.save!

      expect(Category.secured).to match_array([uncategorized])
      expect(Category.secured(Guardian.new(user))).to match_array([uncategorized, category, category_2])
    end
  end

  it "strips leading blanks" do
    expect(Fabricate(:category, name: " music").name).to eq("music")
  end

  it "strips trailing blanks" do
    expect(Fabricate(:category, name: "bugs ").name).to eq("bugs")
  end

  it "strips leading and trailing blanks" do
    expect(Fabricate(:category, name: "  blanks ").name).to eq("blanks")
  end

  it "sets name_lower" do
    expect(Fabricate(:category, name: "Not MySQL").name_lower).to eq("not mysql")
  end

  it "has custom fields" do
    category = Fabricate(:category, name: " music")
    expect(category.custom_fields["a"]).to be_nil

    category.custom_fields["bob"] = "marley"
    category.custom_fields["jack"] = "black"
    category.save

    category = Category.find(category.id)
    expect(category.custom_fields).to eq("bob" => "marley", "jack" => "black")
  end

  describe "short name" do
    let!(:category) { Fabricate(:category, name: 'xx') }

    it "creates the category" do
      expect(category).to be_present
    end

    it 'has one topic' do
      expect(Topic.where(category_id: category.id).count).to eq(1)
    end
  end

  describe 'non-english characters' do
    context 'uses ascii slug generator' do
      before do
        SiteSetting.slug_generation_method = 'ascii'
        @category = Fabricate(:category, name: "测试")
      end
      after { @category.destroy }

      it "creates a blank slug" do
        expect(@category.slug).to be_blank
        expect(@category.slug_for_url).to eq("#{@category.id}-category")
      end
    end

    context 'uses none slug generator' do
      before do
        SiteSetting.slug_generation_method = 'none'
        @category = Fabricate(:category, name: "测试")
      end
      after do
        SiteSetting.slug_generation_method = 'ascii'
        @category.destroy
      end

      it "creates a blank slug" do
        expect(@category.slug).to be_blank
        expect(@category.slug_for_url).to eq("#{@category.id}-category")
      end
    end

    context 'uses encoded slug generator' do
      before do
        SiteSetting.slug_generation_method = 'encoded'
        @category = Fabricate(:category, name: "测试")
      end
      after do
        SiteSetting.slug_generation_method = 'ascii'
        @category.destroy
      end

      it "creates a slug" do
        expect(@category.slug).to eq("测试")
        expect(@category.slug_for_url).to eq("测试")
      end
    end
  end

  describe 'slug would be a number' do
    let(:category) { Fabricate.build(:category, name: "2") }

    it 'creates a blank slug' do
      expect(category.slug).to be_blank
      expect(category.slug_for_url).to eq("#{category.id}-category")
    end
  end

  describe 'custom slug can be provided' do
    it 'can be sanitized' do
      @c = Fabricate(:category, name: "Fun Cats", slug: "fun-cats")
      @cat = Fabricate(:category, name: "love cats", slug: "love-cats")

      @c.slug = '  invalid slug'
      @c.save
      expect(@c.slug).to eq('invalid-slug')

      c = Fabricate.build(:category, name: "More Fun Cats", slug: "love-cats")
      expect(c).not_to be_valid
      expect(c.errors[:slug]).to be_present

      @cat.slug = "#{@c.id}-category"
      expect(@cat).not_to be_valid
      expect(@cat.errors[:slug]).to be_present

      @cat.slug = "#{@cat.id}-category"
      expect(@cat).to be_valid
      expect(@cat.errors[:slug]).not_to be_present
    end
  end

  describe 'description_text' do
    it 'correctly generates text description as needed' do
      c = Category.new
      expect(c.description_text).to be_nil
      c.description = "&lt;hello <a>test</a>."
      expect(c.description_text).to eq("<hello test.")
    end
  end

  describe 'after create' do
    before do
      @category = Fabricate(:category, name: 'Amazing Category')
      @topic = @category.topic
    end

    it 'is created correctly' do
      expect(@category.slug).to eq('amazing-category')
      expect(@category.slug_for_url).to eq(@category.slug)

      expect(@category.description).to be_blank

      expect(Topic.where(category_id: @category).count).to eq(1)

      expect(@topic).to be_present

      expect(@topic.category).to eq(@category)

      expect(@topic).to be_visible

      expect(@topic.pinned_at).to be_present

      expect(Guardian.new(@category.user).can_delete?(@topic)).to be false

      expect(@topic.posts.count).to eq(1)

      expect(@category.topic_url).to be_present

      expect(@category.posts_week).to eq(0)
      expect(@category.posts_month).to eq(0)
      expect(@category.posts_year).to eq(0)

      expect(@category.topics_week).to eq(0)
      expect(@category.topics_month).to eq(0)
      expect(@category.topics_year).to eq(0)
    end

    it "renames the definition when renamed" do
      @category.update_attributes(name: 'Troutfishing')
      @topic.reload
      expect(@topic.title).to match(/Troutfishing/)
      expect(@topic.fancy_title).to match(/Troutfishing/)
    end

    it "doesn't raise an error if there is no definition topic to rename (uncategorized)" do
      expect { @category.update_attributes(name: 'Troutfishing', topic_id: nil) }.to_not raise_error
    end

    it "creates permalink when category slug is changed" do
      @category.update_attributes(slug: 'new-category')
      expect(Permalink.count).to eq(1)
    end

    it "creates permalink when sub category slug is changed" do
      sub_category = Fabricate(:category, slug: 'sub-category', parent_category_id: @category.id)
      sub_category.update_attributes(slug: 'new-sub-category')
      expect(Permalink.count).to eq(1)
    end

    it "deletes permalink when category slug is reused" do
      Fabricate(:permalink, url: "/c/bikeshed-category")
      Fabricate(:category, slug: 'bikeshed-category')
      expect(Permalink.count).to eq(0)
    end

    it "deletes permalink when sub category slug is reused" do
      Fabricate(:permalink, url: "/c/main-category/sub-category")
      main_category = Fabricate(:category, slug: 'main-category')
      Fabricate(:category, slug: 'sub-category', parent_category_id: main_category.id)
      expect(Permalink.count).to eq(0)
    end

    it "should not set its description topic to auto-close" do
      category = Fabricate(:category, name: 'Closing Topics', auto_close_hours: 1)
      expect(category.topic.public_topic_timer).to eq(nil)
    end

    describe "creating a new category with the same slug" do
      it "should have a blank slug if at the same level" do
        category = Fabricate(:category, name: "Amazing Categóry")
        expect(category.slug).to be_blank
        expect(category.slug_for_url).to eq("#{category.id}-category")
      end

      it "doesn't have a blank slug if not at the same level" do
        parent = Fabricate(:category, name: 'Other parent')
        category = Fabricate(:category, name: "Amazing Categóry", parent_category_id: parent.id)
        expect(category.slug).to eq('amazing-category')
        expect(category.slug_for_url).to eq("amazing-category")
      end
    end

    describe "trying to change the category topic's category" do
      before do
        @new_cat = Fabricate(:category, name: '2nd Category', user: @category.user)
        @topic.change_category_to_id(@new_cat.id)
        @topic.reload
        @category.reload
      end

      it 'does not cause changes' do
        expect(@category.topic_count).to eq(0)
        expect(@topic.category).to eq(@category)
        expect(@category.topic).to eq(@topic)
      end
    end
  end

  describe 'new' do
    subject { Fabricate.build(:category, user: Fabricate(:user)) }

    it 'triggers a extensibility event' do
      event = DiscourseEvent.track_events { subject.save! }.last

      expect(event[:event_name]).to eq(:category_created)
      expect(event[:params].first).to eq(subject)
    end
  end

  describe "update" do
    it "should enforce uniqueness of slug" do
      Fabricate(:category, slug: "the-slug")
      c2 = Fabricate(:category, slug: "different-slug")
      c2.slug = "the-slug"
      expect(c2).to_not be_valid
      expect(c2.errors[:slug]).to be_present
    end
  end

  describe 'destroy' do
    before do
      @category = Fabricate(:category)
      @category_id = @category.id
      @topic_id = @category.topic_id
      SiteSetting.shared_drafts_category = @category.id.to_s
    end

    it 'is deleted correctly' do
      @category.destroy
      expect(Category.exists?(id: @category_id)).to be false
      expect(Topic.exists?(id: @topic_id)).to be false
      expect(SiteSetting.shared_drafts_category).to be_blank
    end

    it 'triggers a extensibility event' do
      event = DiscourseEvent.track_events { @category.destroy }.first

      expect(event[:event_name]).to eq(:category_destroyed)
      expect(event[:params].first).to eq(@category)
    end
  end

  describe 'latest' do
    it 'should be updated correctly' do
      category = Fabricate(:category)
      post = create_post(category: category.id)

      category.reload
      expect(category.latest_post_id).to eq(post.id)
      expect(category.latest_topic_id).to eq(post.topic_id)

      post2 = create_post(category: category.id)
      post3 = create_post(topic_id: post.topic_id, category: category.id)

      category.reload
      expect(category.latest_post_id).to eq(post3.id)
      expect(category.latest_topic_id).to eq(post2.topic_id)

      post3.reload

      destroyer = PostDestroyer.new(Fabricate(:admin), post3)
      destroyer.destroy

      category.reload
      expect(category.latest_post_id).to eq(post2.id)
    end
  end

  describe 'update_stats' do
    before do
      @category = Fabricate(:category)
    end

    context 'with regular topics' do
      before do
        create_post(user: @category.user, category: @category.id)
        Category.update_stats
        @category.reload
      end

      it 'updates topic stats' do
        expect(@category.topics_week).to eq(1)
        expect(@category.topics_month).to eq(1)
        expect(@category.topics_year).to eq(1)
        expect(@category.topic_count).to eq(1)
        expect(@category.post_count).to eq(1)
        expect(@category.posts_year).to eq(1)
        expect(@category.posts_month).to eq(1)
        expect(@category.posts_week).to eq(1)
      end

    end

    context 'with deleted topics' do
      before do
        @category.topics << Fabricate(:deleted_topic,
                                      user: @category.user)
        Category.update_stats
        @category.reload
      end

      it 'does not count deleted topics' do
        expect(@category.topics_week).to eq(0)
        expect(@category.topic_count).to eq(0)
        expect(@category.topics_month).to eq(0)
        expect(@category.topics_year).to eq(0)
        expect(@category.post_count).to eq(0)
        expect(@category.posts_year).to eq(0)
        expect(@category.posts_month).to eq(0)
        expect(@category.posts_week).to eq(0)
      end
    end

    context 'with revised post' do
      before do
        post = create_post(user: @category.user, category: @category.id)

        SiteSetting.editing_grace_period = 1.minute
        post.revise(post.user, { raw: 'updated body' }, revised_at: post.updated_at + 2.minutes)

        Category.update_stats
        @category.reload
      end

      it "doesn't count each version of a post" do
        expect(@category.post_count).to eq(1)
        expect(@category.posts_year).to eq(1)
        expect(@category.posts_month).to eq(1)
        expect(@category.posts_week).to eq(1)
      end
    end

    context 'for uncategorized category' do
      before do
        @uncategorized = Category.find(SiteSetting.uncategorized_category_id)
        create_post(user: Fabricate(:user), category: @uncategorized.name)
        Category.update_stats
        @uncategorized.reload
      end

      it 'updates topic stats' do
        expect(@uncategorized.topics_week).to eq(1)
        expect(@uncategorized.topics_month).to eq(1)
        expect(@uncategorized.topics_year).to eq(1)
        expect(@uncategorized.topic_count).to eq(1)
        expect(@uncategorized.post_count).to eq(1)
        expect(@uncategorized.posts_year).to eq(1)
        expect(@uncategorized.posts_month).to eq(1)
        expect(@uncategorized.posts_week).to eq(1)
      end
    end
  end

  describe "#url" do
    it "builds a url for normal categories" do
      category = Fabricate(:category, name: "cats")
      expect(category.url).to eq "/c/cats"
    end

    describe "for subcategories" do
      it "includes the parent category" do
        parent_category = Fabricate(:category, name: "parent")
        subcategory = Fabricate(:category, name: "child",
                                           parent_category_id: parent_category.id)
        expect(subcategory.url).to eq "/c/parent/child"
      end
    end
  end

  describe "#url_with_id" do
    let(:category) { Fabricate(:category, name: 'cats') }

    it "includes the id in the URL" do
      expect(category.url_with_id).to eq("/c/#{category.id}-cats")
    end

    context "child category" do
      let(:child_category) { Fabricate(:category, parent_category_id: category.id, name: 'dogs') }

      it "includes the id in the URL" do
        expect(child_category.url_with_id).to eq("/c/cats/dogs/#{child_category.id}")
      end
    end
  end

  describe "uncategorized" do
    let(:cat) { Category.where(id: SiteSetting.uncategorized_category_id).first }

    it "reports as `uncategorized?`" do
      expect(cat).to be_uncategorized
    end

    it "cannot have a parent category" do
      cat.parent_category_id = Fabricate(:category).id
      expect(cat).to_not be_valid
    end
  end

  describe "parent categories" do
    let(:user) { Fabricate(:user) }
    let(:parent_category) { Fabricate(:category, user: user) }

    it "can be associated with a parent category" do
      sub_category = Fabricate.build(:category, parent_category_id: parent_category.id, user: user)
      expect(sub_category).to be_valid
      expect(sub_category.parent_category).to eq(parent_category)
    end

    it "cannot associate a category with itself" do
      category = Fabricate(:category, user: user)
      category.parent_category_id = category.id
      expect(category).to_not be_valid
    end

    it "cannot have a category two levels deep" do
      sub_category = Fabricate(:category, parent_category_id: parent_category.id, user: user)
      nested_sub_category = Fabricate.build(:category, parent_category_id: sub_category.id, user: user)
      expect(nested_sub_category).to_not be_valid
    end

    describe ".query_parent_category" do
      it "should return the parent category id given a parent slug" do
        parent_category.name = "Amazing Category"
        expect(parent_category.id).to eq(Category.query_parent_category(parent_category.slug))
      end
    end

    describe ".query_category" do
      it "should return the category" do
        category = Fabricate(:category, name: "Amazing Category", parent_category_id: parent_category.id, user: user)
        parent_category.name = "Amazing Parent Category"
        expect(category).to eq(Category.query_category(category.slug, parent_category.id))
      end
    end

  end

  describe "find_by_email" do
    it "is case insensitive" do
      c1 = Fabricate(:category, email_in: 'lower@example.com')
      c2 = Fabricate(:category, email_in: 'UPPER@EXAMPLE.COM')
      c3 = Fabricate(:category, email_in: 'Mixed.Case@Example.COM')
      expect(Category.find_by_email('LOWER@EXAMPLE.COM')).to eq(c1)
      expect(Category.find_by_email('upper@example.com')).to eq(c2)
      expect(Category.find_by_email('mixed.case@example.com')).to eq(c3)
      expect(Category.find_by_email('MIXED.CASE@EXAMPLE.COM')).to eq(c3)
    end
  end

  describe "find_by_slug" do
    it "finds with category and sub category" do
      category = Fabricate(:category, slug: 'awesome-category')
      sub_category = Fabricate(:category, parent_category_id: category.id, slug: 'awesome-sub-category')

      expect(Category.find_by_slug('awesome-category')).to eq(category)
      expect(Category.find_by_slug('awesome-sub-category', 'awesome-category')).to eq(sub_category)
    end
  end

  describe "validate email_in" do
    let(:user) { Fabricate(:user) }

    it "works with a valid email" do
      expect(Category.new(name: 'test', user: user, email_in: 'test@example.com').valid?).to eq(true)
    end

    it "adds an error with an invalid email" do
      category = Category.new(name: 'test', user: user, email_in: '<sup>test</sup>')
      expect(category.valid?).to eq(false)
      expect(category.errors.full_messages.join).not_to match(/<sup>/)
    end

    context "with a duplicate email in a group" do
      let(:group) { Fabricate(:group, name: 'testgroup', incoming_email: 'test@example.com') }

      it "adds an error with an invalid email" do
        category = Category.new(name: 'test', user: user, email_in: group.incoming_email)
        expect(category.valid?).to eq(false)
      end
    end

    context "with duplicate email in a category" do
      let!(:category) { Fabricate(:category, user: user, name: '<b>cool</b>', email_in: 'test@example.com') }

      it "adds an error with an invalid email" do
        category = Category.new(name: 'test', user: user, email_in: "test@example.com")
        expect(category.valid?).to eq(false)
        expect(category.errors.full_messages.join).not_to match(/<b>/)
      end
    end

  end

  describe 'require topic/post approval' do
    let(:category) { Fabricate(:category) }

    describe '#require_topic_approval?' do
      before do
        category.custom_fields[Category::REQUIRE_TOPIC_APPROVAL] = true
        category.save
      end

      it { expect(category.reload.require_topic_approval?).to eq(true) }
    end

    describe '#require_reply_approval?' do
      before do
        category.custom_fields[Category::REQUIRE_REPLY_APPROVAL] = true
        category.save
      end

      it { expect(category.reload.require_reply_approval?).to eq(true) }
    end
  end

  describe 'auto bump' do
    after do
      RateLimiter.disable
    end

    it 'should correctly automatically bump topics' do
      freeze_time 1.second.ago
      category = Fabricate(:category)
      category.clear_auto_bump_cache!

      freeze_time 1.second.from_now
      post1 = create_post(category: category)
      freeze_time 1.second.from_now
      _post2 = create_post(category: category)
      freeze_time 1.second.from_now
      _post3 = create_post(category: category)

      # no limits on post creation or category creation please
      RateLimiter.enable

      time = 1.month.from_now
      freeze_time time

      expect(category.auto_bump_topic!).to eq(false)
      expect(Topic.where(bumped_at: time).count).to eq(0)

      category.num_auto_bump_daily = 2
      category.save!

      expect(category.auto_bump_topic!).to eq(true)
      expect(Topic.where(bumped_at: time).count).to eq(1)
      # our extra bump message
      expect(post1.topic.reload.posts_count).to eq(2)

      time = time + 13.hours
      freeze_time time

      expect(category.auto_bump_topic!).to eq(true)
      expect(Topic.where(bumped_at: time).count).to eq(1)

      expect(category.auto_bump_topic!).to eq(false)
      expect(Topic.where(bumped_at: time).count).to eq(1)

      time = 1.month.from_now
      freeze_time time

      category.auto_bump_limiter.clear!
      expect(Category.auto_bump_topic!).to eq(true)
      expect(Topic.where(bumped_at: time).count).to eq(1)

      category.num_auto_bump_daily = ""
      category.save!

      expect(Category.auto_bump_topic!).to eq(false)
    end
  end

end
