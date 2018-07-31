require 'rails_helper'

describe CategoriesController do
  let(:admin) { Fabricate(:admin) }
  let!(:category) { Fabricate(:category, user: admin) }

  context 'index' do

    it 'suppresses categories correctly' do
      post = create_post(title: 'super AMAZING AMAZING post')

      get "/categories"
      expect(response.body).to include('AMAZING AMAZING')

      post.topic.category.update_columns(suppress_from_latest: true)

      get "/categories"
      expect(response.body).not_to include('AMAZING AMAZING')
    end

    it 'web crawler view has correct urls for subfolder install' do
      GlobalSetting.stubs(:relative_url_root).returns('/forum')
      Discourse.stubs(:base_uri).returns("/forum")
      get '/categories', headers: { 'HTTP_USER_AGENT' => 'Googlebot' }
      html = Nokogiri::HTML(response.body)
      expect(html.css('body.crawler')).to be_present
      expect(html.css("a[href=\"/forum/c/#{category.slug}\"]")).to be_present
    end
  end

  context 'extensibility event' do
    before do
      sign_in(admin)
    end

    it "triggers a extensibility event" do
      event = DiscourseEvent.track_events {
        put "/categories/#{category.id}.json", params: {
          name: 'hello',
          color: 'ff0',
          text_color: 'fff'
        }
      }.last

      expect(event[:event_name]).to eq(:category_updated)
      expect(event[:params].first).to eq(category)
    end
  end

  context '#create' do
    it "requires the user to be logged in" do
      post "/categories.json"
      expect(response.status).to eq(403)
    end

    describe "logged in" do
      before do
        SiteSetting.queue_jobs = false
        sign_in(admin)
      end

      it "raises an exception when they don't have permission to create it" do
        sign_in(Fabricate(:user))
        post "/categories.json", params: {
          name: 'hello', color: 'ff0', text_color: 'fff'
        }

        expect(response).to be_forbidden
      end

      it "raises an exception when the name is missing" do
        post "/categories.json", params: { color: "ff0", text_color: "fff" }
        expect(response.status).to eq(400)
      end

      it "raises an exception when the color is missing" do
        post "/categories.json", params: { name: "hello", text_color: "fff" }
        expect(response.status).to eq(400)
      end

      it "raises an exception when the text color is missing" do
        post "/categories.json", params: { name: "hello", color: "ff0" }
      end

      describe "failure" do
        it "returns errors on a duplicate category name" do
          category = Fabricate(:category, user: admin)

          post "/categories.json", params: {
            name: category.name, color: "ff0", text_color: "fff"
          }

          expect(response.status).to eq(422)
        end
      end

      describe "success" do
        it "works" do
          readonly = CategoryGroup.permission_types[:readonly]
          create_post = CategoryGroup.permission_types[:create_post]

          post "/categories.json", params: {
            name: "hello",
            color: "ff0",
            text_color: "fff",
            slug: "hello-cat",
            auto_close_hours: 72,
            permissions: {
              "everyone" => readonly,
              "staff" => create_post
            }
          }

          expect(response.status).to eq(200)
          category = Category.find_by(name: "hello")
          expect(category.category_groups.map { |g| [g.group_id, g.permission_type] }.sort).to eq([
            [Group[:everyone].id, readonly], [Group[:staff].id, create_post]
          ])
          expect(category.name).to eq("hello")
          expect(category.slug).to eq("hello-cat")
          expect(category.color).to eq("ff0")
          expect(category.auto_close_hours).to eq(72)
          expect(UserHistory.count).to eq(4) # 1 + 3 (bootstrap mode)
        end
      end
    end
  end

  context '#destroy' do
    it "requires the user to be logged in" do
      delete "/categories/category.json"
      expect(response.status).to eq(403)
    end

    describe "logged in" do
      it "raises an exception if they don't have permission to delete it" do
        admin.update!(admin: false)
        sign_in(admin)
        delete "/categories/#{category.slug}.json"
        expect(response).to be_forbidden
      end

      it "deletes the record" do
        sign_in(admin)
        expect do
          delete "/categories/#{category.slug}.json"
        end.to change(Category, :count).by(-1)
        expect(response.status).to eq(200)
        expect(UserHistory.count).to eq(1)
      end
    end
  end

  context '#reorder' do
    it "reorders the categories" do
      sign_in(admin)

      c1 = category
      c2 = Fabricate(:category)
      c3 = Fabricate(:category)
      c4 = Fabricate(:category)
      if c3.id < c2.id
        tmp = c3; c2 = c3; c3 = tmp;
      end
      c1.position = 8
      c2.position = 6
      c3.position = 7
      c4.position = 5

      payload = {}
      payload[c1.id] = 4
      payload[c2.id] = 6
      payload[c3.id] = 6
      payload[c4.id] = 5

      post "/categories/reorder.json", params: { mapping: MultiJson.dump(payload) }

      SiteSetting.fixed_category_positions = true
      list = CategoryList.new(Guardian.new(admin))

      expect(list.categories).to eq([
        Category.find(SiteSetting.uncategorized_category_id),
        c1,
        c4,
        c2,
        c3
      ])
    end
  end

  context '#update' do
    before do
      SiteSetting.queue_jobs = false
    end

    it "requires the user to be logged in" do
      put "/categories/category.json"
      expect(response.status).to eq(403)
    end

    describe "logged in" do
      let(:valid_attrs) { { id: category.id, name: "hello", color: "ff0", text_color: "fff" } }

      before do
        sign_in(admin)
      end

      it "raises an exception if they don't have permission to edit it" do
        sign_in(Fabricate(:user))
        put "/categories/#{category.slug}.json", params: {
          name: 'hello',
          color: 'ff0',
          text_color: 'fff'
        }
        expect(response).to be_forbidden
      end

      it "requires a name" do
        put "/categories/#{category.slug}.json", params: {
          color: 'fff',
          text_color: '0ff',
        }
        expect(response.status).to eq(400)
      end

      it "requires a color" do
        put "/categories/#{category.slug}.json", params: {
          name: 'asdf',
          text_color: '0ff',
        }
        expect(response.status).to eq(400)
      end

      it "requires a text color" do
        put "/categories/#{category.slug}.json", params: { name: 'asdf', color: 'fff' }
        expect(response.status).to eq(400)
      end

      it "returns errors on a duplicate category name" do
        other_category = Fabricate(:category, name: "Other", user: admin)
        put "/categories/#{category.id}.json", params: {
          name: other_category.name,
          color: "ff0",
          text_color: "fff",
        }
        expect(response.status).to eq(422)
      end

      it "returns 422 if email_in address is already in use for other category" do
        other_category = Fabricate(:category, name: "Other", email_in: "mail@examle.com")

        put "/categories/#{category.id}.json", params: {
          name: "Email",
          email_in: "mail@examle.com",
          color: "ff0",
          text_color: "fff",
        }
        expect(response.status).to eq(422)
      end

      describe "success" do
        it "updates the group correctly" do
          readonly = CategoryGroup.permission_types[:readonly]
          create_post = CategoryGroup.permission_types[:create_post]

          put "/categories/#{category.id}.json", params: {
            name: "hello",
            color: "ff0",
            text_color: "fff",
            slug: "hello-category",
            auto_close_hours: 72,
            permissions: {
              "everyone" => readonly,
              "staff" => create_post
            },
            custom_fields: {
              "dancing" => "frogs"
            },
          }

          expect(response.status).to eq(200)
          category.reload
          expect(category.category_groups.map { |g| [g.group_id, g.permission_type] }.sort).to eq([
            [Group[:everyone].id, readonly], [Group[:staff].id, create_post]
          ])
          expect(category.name).to eq("hello")
          expect(category.slug).to eq("hello-category")
          expect(category.color).to eq("ff0")
          expect(category.auto_close_hours).to eq(72)
          expect(category.custom_fields).to eq("dancing" => "frogs")
        end

        it 'logs the changes correctly' do
          category.update!(permissions: { "admins" => CategoryGroup.permission_types[:create_post] })

          put "/categories/#{category.id}.json", params: {
            name: 'new name',
            color: category.color,
            text_color: category.text_color,
            slug: category.slug,
            permissions: {
              "everyone" => CategoryGroup.permission_types[:create_post]
            },
          }
          expect(response.status).to eq(200)
          expect(UserHistory.count).to eq(5) # 2 + 3 (bootstrap mode)
        end

        it 'updates per-category settings correctly' do
          category.custom_fields[Category::REQUIRE_TOPIC_APPROVAL] = false
          category.custom_fields[Category::REQUIRE_REPLY_APPROVAL] = false
          category.custom_fields[Category::NUM_AUTO_BUMP_DAILY] = 0

          category.navigate_to_first_post_after_read = false
          category.save!

          put "/categories/#{category.id}.json", params: {
            name: category.name,
            color: category.color,
            text_color: category.text_color,
            navigate_to_first_post_after_read: true,
            custom_fields: {
              require_reply_approval: true,
              require_topic_approval: true,
              num_auto_bump_daily: 10
            }
          }

          category.reload
          expect(category.require_topic_approval?).to eq(true)
          expect(category.require_reply_approval?).to eq(true)
          expect(category.num_auto_bump_daily).to eq(10)
          expect(category.navigate_to_first_post_after_read).to eq(true)
        end
      end
    end
  end

  context '#update_slug' do
    it 'requires the user to be logged in' do
      put "/category/category/slug.json"
      expect(response.status).to eq(403)
    end

    describe 'logged in' do
      let(:valid_attrs) { { id: category.id, slug: 'fff' } }

      before do
        sign_in(admin)
      end

      it 'rejects blank' do
        put "/category/#{category.id}/slug.json", params: { slug: nil }
        expect(response.status).to eq(422)
      end

      it 'accepts valid custom slug' do
        put "/category/#{category.id}/slug.json", params: { slug: 'valid-slug' }

        expect(response.status).to eq(200)
        expect(category.reload.slug).to eq('valid-slug')
      end

      it 'accepts not well formed custom slug' do
        put "/category/#{category.id}/slug.json", params: { slug: ' valid slug' }

        expect(response.status).to eq(200)
        expect(category.reload.slug).to eq('valid-slug')
      end

      it 'accepts and sanitize custom slug when the slug generation method is not ascii' do
        SiteSetting.slug_generation_method = 'none'
        put "/category/#{category.id}/slug.json", params: { slug: ' another !_ slug @' }

        expect(response.status).to eq(200)
        expect(category.reload.slug).to eq('another-slug')
        SiteSetting.slug_generation_method = 'ascii'
      end

      it 'rejects invalid custom slug' do
        put "/category/#{category.id}/slug.json", params: { slug: '  ' }
        expect(response.status).to eq(422)
      end
    end
  end
end
