require 'rails_helper'

describe GroupsController do
  let(:user) { Fabricate(:user) }
  let(:group) { Fabricate(:group, users: [user]) }
  let(:moderator_group_id) { Group::AUTO_GROUPS[:moderators] }
  let(:admin) { Fabricate(:admin) }
  let(:moderator) { Fabricate(:moderator) }

  describe '#index' do
    let(:staff_group) do
      Fabricate(:group, name: '0000', visibility_level: Group.visibility_levels[:staff])
    end

    context 'when group directory is disabled' do
      before do
        SiteSetting.enable_group_directory = false
      end

      it 'should deny access for an anon user' do
        get "/groups.json"
        expect(response.status).to eq(403)
      end

      it 'should deny access for a normal user' do
        get "/groups.json"
        expect(response.status).to eq(403)
      end

      it 'should not deny access for an admin' do
        sign_in(admin)
        get "/groups.json"

        expect(response.status).to eq(200)
      end

      it 'should not deny access for a moderator' do
        sign_in(moderator)
        get "/groups.json"

        expect(response.status).to eq(200)
      end
    end

    context 'searchable' do
      it 'should return the right response' do
        other_group = Fabricate(:group, name: 'testing')
        get "/groups.json", params: { filter: 'test' }

        expect(response.status).to eq(200)
        expect(JSON.parse(response.body)["groups"].first["id"]).to eq(other_group.id)
      end
    end

    context 'sortable' do
      let!(:other_group) { Fabricate(:group, name: "zzzzzz", users: [user]) }

      %w{
        desc
        asc
      }.each do |order|
        context "#{order} order" do
          it 'should return the right response' do
            is_asc = order == 'asc'
            params = { order: 'name' }
            params.merge!(asc: true) if is_asc
            group
            get "/groups.json", params: params

            expect(response.status).to eq(200)

            group_ids = [moderator_group_id, group.id, other_group.id]
            group_ids.reverse! if !is_asc

            expect(JSON.parse(response.body)["groups"].map { |group| group["id"] })
              .to eq(group_ids)
          end
        end
      end

      context 'ascending order' do
        it 'should return the right response' do
          group
          get "/groups.json", params: { order: 'name' }

          expect(response.status).to eq(200)

          expect(JSON.parse(response.body)["groups"].map { |group| group["id"] })
            .to eq([other_group.id, group.id, moderator_group_id])
        end
      end
    end

    it 'should return the right response' do
      group
      staff_group
      get "/groups.json"

      expect(response.status).to eq(200)

      response_body = JSON.parse(response.body)

      group_ids = response_body["groups"].map { |g| g["id"] }

      expect(group_ids).to include(group.id)
      expect(group_ids).to_not include(staff_group.id)
      expect(response_body["load_more_groups"]).to eq("/groups?page=1")
      expect(response_body["total_rows_groups"]).to eq(2)

      expect(response_body["extras"]["type_filters"].map(&:to_sym)).to eq(
        described_class::TYPE_FILTERS.keys - [:my, :owner, :automatic]
      )
    end

    context 'viewing groups of another user' do
      describe 'when an invalid username is given' do
        it 'should return the right response' do
          group
          get "/groups.json", params: { username: 'asdasd' }

          expect(response.status).to eq(404)
        end
      end

      it 'should return the right response' do
        user2 = Fabricate(:user)
        group
        sign_in(user2)

        get "/groups.json", params: { username: user.username }

        expect(response.status).to eq(200)

        response_body = JSON.parse(response.body)

        group_ids = response_body["groups"].map { |g| g["id"] }

        expect(group_ids).to contain_exactly(group.id)
      end
    end

    context 'viewing as an admin' do
      let(:admin) { Fabricate(:admin) }

      before do
        sign_in(admin)
        group.add(admin)
        group.add_owner(admin)
      end

      it 'should return the right response' do
        staff_group
        get "/groups.json"

        expect(response.status).to eq(200)

        response_body = JSON.parse(response.body)

        group_ids = response_body["groups"].map { |g| g["id"] }
        group_body = response_body["groups"].find { |g| g["id"] == group.id }

        expect(group_body["is_group_user"]).to eq(true)
        expect(group_body["is_group_owner"]).to eq(true)
        expect(group_ids).to include(group.id, staff_group.id)
        expect(response_body["load_more_groups"]).to eq("/groups?page=1")
        expect(response_body["total_rows_groups"]).to eq(10)

        expect(response_body["extras"]["type_filters"].map(&:to_sym)).to eq(
          described_class::TYPE_FILTERS.keys
        )
      end

      context 'filterable by type' do
        def expect_type_to_return_right_groups(type, expected_group_ids)
          get "/groups.json", params: { type: type }

          expect(response.status).to eq(200)

          response_body = JSON.parse(response.body)
          group_ids = response_body["groups"].map { |g| g["id"] }

          expect(response_body["total_rows_groups"]).to eq(expected_group_ids.count)
          expect(group_ids).to contain_exactly(*expected_group_ids)
        end

        describe 'my groups' do
          it 'should return the right response' do
            expect_type_to_return_right_groups('my', [group.id])
          end
        end

        describe 'owner groups' do
          it 'should return the right response' do
            group2 = Fabricate(:group)
            _group3 = Fabricate(:group)
            group2.add_owner(admin)

            expect_type_to_return_right_groups('owner', [group.id, group2.id])
          end
        end

        describe 'automatic groups' do
          it 'should return the right response' do
            expect_type_to_return_right_groups(
              'automatic',
              Group::AUTO_GROUP_IDS.keys - [0]
            )
          end
        end

        describe 'public groups' do
          it 'should return the right response' do
            group2 = Fabricate(:group, public_admission: true)

            expect_type_to_return_right_groups('public', [group2.id])
          end
        end

        describe 'close groups' do
          it 'should return the right response' do
            group2 = Fabricate(:group, public_admission: false)
            _group3 = Fabricate(:group, public_admission: true)

            expect_type_to_return_right_groups('close', [group.id, group2.id])
          end
        end
      end
    end
  end

  describe '#show' do
    it "ensures the group can be seen" do
      sign_in(Fabricate(:user))
      group.update!(visibility_level: Group.visibility_levels[:owners])

      get "/groups/#{group.name}.json"

      expect(response.status).to eq(403)
    end

    it "returns the right response" do
      sign_in(user)
      get "/groups/#{group.name}.json"

      expect(response.status).to eq(200)

      response_body = JSON.parse(response.body)

      expect(response_body['group']['id']).to eq(group.id)
      expect(response_body['extras']["visible_group_names"]).to eq([group.name])
    end

    context 'as an admin' do
      it "returns the right response" do
        sign_in(Fabricate(:admin))
        get "/groups/#{group.name}.json"

        expect(response.status).to eq(200)

        response_body = JSON.parse(response.body)

        expect(response_body['group']['id']).to eq(group.id)

        groups = Group::AUTO_GROUPS.keys
        groups.delete(:everyone)
        groups.push(group.name)

        expect(response_body['extras']["visible_group_names"])
          .to contain_exactly(*groups.map(&:to_s))
      end
    end

    it 'should respond to HTML' do
      group.update_attribute(:bio_cooked, 'testing group bio')

      get "/groups/#{group.name}.html"

      expect(response.status).to eq(200)

      expect(response.body).to have_tag(:meta, with: {
        property: 'og:title', content: group.name
      })

      expect(response.body).to have_tag(:meta, with: {
        property: 'og:description', content: group.bio_cooked
      })
    end

    describe 'when viewing activity filters' do
      it 'should return the right response' do
        get "/groups/#{group.name}/activity/posts.json"

        expect(response.status).to eq(200)

        response_body = JSON.parse(response.body)['group']

        expect(response_body["id"]).to eq(group.id)
      end
    end
  end

  describe "#posts" do
    it "ensures the group can be seen" do
      sign_in(Fabricate(:user))
      group.update!(visibility_level: Group.visibility_levels[:owners])

      get "/groups/#{group.name}/posts.json"

      expect(response.status).to eq(403)
    end

    it "calls `posts_for` and responds with JSON" do
      sign_in(user)
      post = Fabricate(:post, user: user)
      get "/groups/#{group.name}/posts.json"

      expect(response.status).to eq(200)
      expect(JSON.parse(response.body).first["id"]).to eq(post.id)
    end
  end

  describe "#members" do

    it "returns correct error code with invalid params" do
      sign_in(Fabricate(:user))

      get "/groups/#{group.name}/members.json?limit=-1"
      expect(response.status).to eq(400)

      get "/groups/#{group.name}/members.json?offset=-1"
      expect(response.status).to eq(400)
    end

    it "ensures the group can be seen" do
      sign_in(Fabricate(:user))
      group.update!(visibility_level: Group.visibility_levels[:owners])

      get "/groups/#{group.name}/members.json"

      expect(response.status).to eq(403)
    end

    it "ensures that membership can be paginated" do
      5.times { group.add(Fabricate(:user)) }
      usernames = group.users.map { |m| m.username }.sort

      get "/groups/#{group.name}/members.json", params: { limit: 3 }

      expect(response.status).to eq(200)

      members = JSON.parse(response.body)["members"]

      expect(members.map { |m| m['username'] }).to eq(usernames[0..2])

      get "/groups/#{group.name}/members.json", params: { limit: 3, offset: 3 }

      expect(response.status).to eq(200)

      members = JSON.parse(response.body)["members"]

      expect(members.map { |m| m['username'] }).to eq(usernames[3..5])
    end
  end

  describe '#posts_feed' do
    it 'renders RSS' do
      get "/groups/#{group.name}/posts.rss"

      expect(response.status).to eq(200)
      expect(response.content_type).to eq('application/rss+xml')
    end
  end

  describe '#mentions_feed' do
    it 'renders RSS' do
      get "/groups/#{group.name}/mentions.rss"

      expect(response.status).to eq(200)
      expect(response.content_type).to eq('application/rss+xml')
    end

    it 'fails when disabled' do
      SiteSetting.enable_mentions = false

      get "/groups/#{group.name}/mentions.rss"

      expect(response.status).to eq(404)
    end
  end

  describe '#mentionable' do
    it "should return the right response" do
      sign_in(user)

      get "/groups/#{group.name}/mentionable.json"
      expect(response.status).to eq(200)

      response_body = JSON.parse(response.body)
      expect(response_body["mentionable"]).to eq(false)

      group.update_attributes!(
        mentionable_level: Group::ALIAS_LEVELS[:everyone],
        visibility_level: Group.visibility_levels[:staff]
      )

      get "/groups/#{group.name}/mentionable.json"
      expect(response.status).to eq(200)

      response_body = JSON.parse(response.body)
      expect(response_body["mentionable"]).to eq(true)
    end
  end

  describe '#messageable' do
    it "should return the right response" do
      sign_in(user)

      get "/groups/#{group.name}/messageable.json"
      expect(response.status).to eq(200)

      response_body = JSON.parse(response.body)
      expect(response_body["messageable"]).to eq(false)

      group.update!(
        messageable_level: Group::ALIAS_LEVELS[:everyone],
        visibility_level: Group.visibility_levels[:staff]
      )

      get "/groups/#{group.name}/messageable.json"
      expect(response.status).to eq(200)

      response_body = JSON.parse(response.body)
      expect(response_body["messageable"]).to eq(true)
    end
  end

  describe '#update' do
    let(:group) do
      Fabricate(:group,
        name: 'test',
        users: [user],
        public_admission: false,
        public_exit: false
      )
    end

    context "when user is group owner" do
      before do
        group.add_owner(user)
        sign_in(user)
      end

      it "should be able update the group" do
        group.update!(
          allow_membership_requests: false,
          visibility_level: 2,
          mentionable_level: 2,
          messageable_level: 2,
          default_notification_level: 0,
          grant_trust_level: 0,
          automatic_membership_retroactive: false
        )

        expect do
          put "/groups/#{group.id}.json", params: {
            group: {
              mentionable_level: 1,
              messageable_level: 1,
              visibility_level: 1,
              automatic_membership_email_domains: 'test.org',
              automatic_membership_retroactive: true,
              title: 'haha',
              primary_group: true,
              grant_trust_level: 1,
              incoming_email: 'test@mail.org',
              flair_bg_color: 'FFF',
              flair_color: 'BBB',
              flair_url: 'fa-adjust',
              bio_raw: 'testing',
              full_name: 'awesome team',
              public_admission: true,
              public_exit: true,
              allow_membership_requests: true,
              membership_request_template: 'testing',
              default_notification_level: 1,
              name: 'testing'
            }
          }
        end.to change { GroupHistory.count }.by(13)

        expect(response.status).to eq(200)

        group.reload

        expect(group.flair_bg_color).to eq('FFF')
        expect(group.flair_color).to eq('BBB')
        expect(group.flair_url).to eq('fa-adjust')
        expect(group.bio_raw).to eq('testing')
        expect(group.full_name).to eq('awesome team')
        expect(group.public_admission).to eq(true)
        expect(group.public_exit).to eq(true)
        expect(group.allow_membership_requests).to eq(true)
        expect(group.membership_request_template).to eq('testing')
        expect(group.name).to eq('test')
        expect(group.visibility_level).to eq(2)
        expect(group.mentionable_level).to eq(1)
        expect(group.messageable_level).to eq(1)
        expect(group.default_notification_level).to eq(1)
        expect(group.automatic_membership_email_domains).to eq(nil)
        expect(group.automatic_membership_retroactive).to eq(false)
        expect(group.title).to eq('haha')
        expect(group.primary_group).to eq(false)
        expect(group.incoming_email).to eq(nil)
        expect(group.grant_trust_level).to eq(0)
      end

      it 'should not be allowed to update automatic groups' do
        group = Group.find(Group::AUTO_GROUPS[:admins])

        put "/groups/#{group.id}.json", params: {
          group: {
            messageable_level: 1
          }
        }

        expect(response.status).to eq(403)
      end
    end

    context "when user is group admin" do
      before do
        user.update_attributes!(admin: true)
        sign_in(user)
      end

      it 'should be able to update the group' do
        group.update!(
          visibility_level: 2,
          automatic_membership_retroactive: false,
          grant_trust_level: 0
        )

        put "/groups/#{group.id}.json", params: {
          group: {
            flair_color: 'BBB',
            name: 'testing',
            incoming_email: 'test@mail.org',
            primary_group: true,
            automatic_membership_email_domains: 'test.org',
            automatic_membership_retroactive: true,
            grant_trust_level: 2,
            visibility_level: 1
          }
        }

        expect(response.status).to eq(200)

        group.reload
        expect(group.flair_color).to eq('BBB')
        expect(group.name).to eq('testing')
        expect(group.incoming_email).to eq("test@mail.org")
        expect(group.primary_group).to eq(true)
        expect(group.visibility_level).to eq(1)
        expect(group.automatic_membership_email_domains).to eq('test.org')
        expect(group.automatic_membership_retroactive).to eq(true)
        expect(group.grant_trust_level).to eq(2)

        expect(Jobs::AutomaticGroupMembership.jobs.first["args"].first["group_id"])
          .to eq(group.id)
      end

      it "should be able to update an automatic group" do
        group = Group.find(Group::AUTO_GROUPS[:admins])

        group.update!(
          visibility_level: 2,
          mentionable_level: 2,
          messageable_level: 2,
          default_notification_level: 2
        )

        put "/groups/#{group.id}.json", params: {
          group: {
            flair_color: 'BBB',
            name: 'testing',
            visibility_level: 1,
            mentionable_level: 1,
            messageable_level: 1,
            default_notification_level: 1
          }
        }

        expect(response.status).to eq(200)

        group.reload
        expect(group.flair_color).to eq(nil)
        expect(group.name).to eq('admins')
        expect(group.visibility_level).to eq(1)
        expect(group.mentionable_level).to eq(1)
        expect(group.messageable_level).to eq(1)
        expect(group.default_notification_level).to eq(1)
      end

      it 'triggers a extensibility event' do
        event = DiscourseEvent.track_events {
          put "/groups/#{group.id}.json", params: { group: { flair_color: 'BBB' } }
        }.last

        expect(event[:event_name]).to eq(:group_updated)
        expect(event[:params].first).to eq(group)
      end
    end

    context "when user is not a group owner or admin" do
      it 'should not be able to update the group' do
        sign_in(user)

        put "/groups/#{group.id}.json", params: { group: { name: 'testing' } }

        expect(response.status).to eq(403)
      end
    end
  end

  describe '#members' do
    let(:user1) do
      Fabricate(:user,
        last_seen_at: Time.zone.now,
        last_posted_at: Time.zone.now - 1.day,
        email: 'b@test.org'
      )
    end

    let(:user2) do
      Fabricate(:user,
        last_seen_at: Time.zone.now - 1 .day,
        last_posted_at: Time.zone.now,
        email: 'a@test.org'
      )
    end

    let(:user3) do
      Fabricate(:user,
        last_seen_at: nil,
        last_posted_at: nil,
        email: 'c@test.org'
      )
    end

    let(:bot) { Fabricate(:user, id: -999) }

    let(:group) { Fabricate(:group, users: [user1, user2, user3, bot]) }

    it "should allow members to be sorted by" do
      get "/groups/#{group.name}/members.json", params: {
        order: 'last_seen_at', desc: true
      }

      expect(response.status).to eq(200)

      members = JSON.parse(response.body)["members"]

      expect(members.map { |m| m["id"] }).to eq([user1.id, user2.id, user3.id])

      get "/groups/#{group.name}/members.json", params: { order: 'last_seen_at' }

      expect(response.status).to eq(200)

      members = JSON.parse(response.body)["members"]

      expect(members.map { |m| m["id"] }).to eq([user2.id, user1.id, user3.id])

      get "/groups/#{group.name}/members.json", params: {
        order: 'last_posted_at', desc: true
      }

      expect(response.status).to eq(200)

      members = JSON.parse(response.body)["members"]

      expect(members.map { |m| m["id"] }).to eq([user2.id, user1.id, user3.id])
    end

    it "should not allow members to be sorted by columns that are not allowed" do
      get "/groups/#{group.name}/members.json", params: { order: 'email' }

      expect(response.status).to eq(200)

      members = JSON.parse(response.body)["members"]

      expect(members.map { |m| m["id"] })
        .to contain_exactly(user1.id, user2.id, user3.id)
    end

    describe 'filterable' do
      describe 'as a normal user' do
        it "should not allow members to be filterable by email" do
          email = 'uniquetest@discourse.org'
          user1.update!(email: email)

          get "/groups/#{group.name}/members.json", params: { filter: email }

          expect(response.status).to eq(200)
          members = JSON.parse(response.body)["members"]
          expect(members).to eq([])
        end
      end

      describe 'as an admin' do
        before do
          sign_in(Fabricate(:admin))
        end

        it "should allow members to be filterable by username" do
          email = 'uniquetest@discourse.org'
          user1.update!(email: email)

          {
            email.upcase => [user1.id],
            'QUEtes' => [user1.id],
            "#{user1.email},#{user2.email}" => [user1.id, user2.id]
          }.each do |filter, ids|
            get "/groups/#{group.name}/members.json", params: { filter: filter }

            expect(response.status).to eq(200)
            members = JSON.parse(response.body)["members"]
            expect(members.map { |m| m["id"] }).to contain_exactly(*ids)
          end
        end

        it "should allow members to be filterable by email" do
          username = 'uniquetest'
          user1.update!(username: username)

          [username.upcase, 'QUEtes'].each do |filter|
            get "/groups/#{group.name}/members.json", params: { filter: filter }

            expect(response.status).to eq(200)
            members = JSON.parse(response.body)["members"]
            expect(members.map { |m| m["id"] }).to contain_exactly(user1.id)
          end
        end
      end
    end
  end

  describe "#edit" do
    let(:group) { Fabricate(:group) }

    context 'when user is not signed in' do
      it 'should be fobidden' do
        put "/groups/#{group.id}/members.json", params: { usernames: "bob" }
        expect(response).to be_forbidden

        delete "/groups/#{group.id}/members.json", params: { username: "bob" }
        expect(response).to be_forbidden
      end

      context 'public group' do
        it 'should be fobidden' do
          group.update_attributes!(
            public_admission: true,
            public_exit: true
          )

          put "/groups/#{group.id}/members.json", params: { usernames: "bob" }
          expect(response.status).to eq(403)

          delete "/groups/#{group.id}/members.json", params: { username: "bob" }
          expect(response.status).to eq(403)
        end
      end
    end

    context 'when user is not an owner of the group' do
      before do
        sign_in(user)
      end

      it "refuses membership changes to unauthorized users" do
        put "/groups/#{group.id}/members.json", params: { usernames: "bob" }
        expect(response).to be_forbidden

        delete "/groups/#{group.id}/members.json", params: { username: "bob" }
        expect(response).to be_forbidden
      end
    end

    context 'when user is an admin' do
      let(:user) { Fabricate(:admin) }
      let(:group) { Fabricate(:group, users: [user], automatic: true) }

      before do
        sign_in(user)
      end

      it "cannot add members to automatic groups" do
        put "/groups/#{group.id}/members.json", params: { usernames: "bob" }
        expect(response).to be_forbidden

        delete "/groups/#{group.id}/members.json", params: { username: "bob" }
        expect(response).to be_forbidden
      end
    end
  end

  describe "membership edits" do
    let(:admin) { Fabricate(:admin) }

    before do
      sign_in(admin)
    end

    context '#add_members' do
      it "can make incremental adds" do
        user2 = Fabricate(:user)

        expect do
          put "/groups/#{group.id}/members.json", params: { usernames: user2.username }
        end.to change { group.users.count }.by(1)

        expect(response.status).to eq(200)

        group_history = GroupHistory.last

        expect(group_history.action).to eq(GroupHistory.actions[:add_user_to_group])
        expect(group_history.acting_user).to eq(admin)
        expect(group_history.target_user).to eq(user2)
      end

      it "cannot add members to automatic groups" do
        group.update!(automatic: true)

        put "/groups/#{group.id}/members.json", params: { usernames: "l77t" }
        expect(response.status).to eq(403)
      end

      context "is able to add several members to a group" do
        let(:user1) { Fabricate(:user) }
        let(:user2) { Fabricate(:user) }

        it "adds by username" do
          expect do
            put "/groups/#{group.id}/members.json",
              params: { usernames: [user1.username, user2.username].join(",") }
          end.to change { group.users.count }.by(2)

          expect(response.status).to eq(200)
        end

        it "adds by id" do
          expect do
            put "/groups/#{group.id}/members.json",
              params: { user_ids: [user1.id, user2.id].join(",") }
          end.to change { group.users.count }.by(2)

          expect(response.status).to eq(200)
        end

        it "adds by email" do
          expect do
            put "/groups/#{group.id}/members.json",
              params: { user_emails: [user1.email, user2.email].join(",") }
          end.to change { group.users.count }.by(2)

          expect(response.status).to eq(200)
        end

        it 'fails when multiple member already exists' do
          user2.update!(username: 'alice')
          user3 = Fabricate(:user, username: 'bob')
          [user2, user3].each { |user| group.add(user) }

          expect do
            put "/groups/#{group.id}/members.json",
              params: { user_emails: [user1.email, user2.email, user3.email].join(",") }
          end.to change { group.users.count }.by(0)

          expect(response.status).to eq(422)

          expect(JSON.parse(response.body)["errors"]).to include(I18n.t(
            "groups.errors.member_already_exist",
            username: "alice, bob",
            count: 2
          ))
        end
      end

      it "returns 422 if member already exists" do
        put "/groups/#{group.id}/members.json", params: { usernames: user.username }

        expect(response.status).to eq(422)

        expect(JSON.parse(response.body)["errors"]).to include(I18n.t(
          "groups.errors.member_already_exist",
          username: user.username,
          count: 1
        ))
      end

      it "returns 400 if member is not found" do
        [
          { usernames: "some thing" },
          { user_ids: "-5,-6" },
          { user_emails: "some@test.org" }
        ].each do |params|
          put "/groups/#{group.id}/members.json", params: params

          expect(response.status).to eq(400)

          body = JSON.parse(response.body)

          expect(body["error_type"]).to eq("invalid_parameters")
        end
      end

      context 'public group' do
        let(:other_user) { Fabricate(:user) }

        before do
          group.update!(
            public_admission: true,
            public_exit: true
          )
        end

        context 'admin' do
          it "can make incremental adds" do
            expect do
              put "/groups/#{group.id}/members.json",
                params: { usernames: other_user.username }
            end.to change { group.users.count }.by(1)

            expect(response.status).to eq(200)

            group_history = GroupHistory.last

            expect(group_history.action).to eq(GroupHistory.actions[:add_user_to_group])
            expect(group_history.acting_user).to eq(admin)
            expect(group_history.target_user).to eq(other_user)
          end
        end

        it 'should allow a user to join the group' do
          sign_in(other_user)

          expect do
            put "/groups/#{group.id}/members.json",
              params: { usernames: other_user.username }
          end.to change { group.users.count }.by(1)

          expect(response.status).to eq(200)
        end

        it 'should not allow an underprivilege user to add another user to a group' do
          sign_in(user)

          put "/groups/#{group.id}/members.json",
            params: { usernames: other_user.username }

          expect(response).to be_forbidden
        end
      end
    end

    context '#remove_member' do
      it "cannot remove members from automatic groups" do
        group.update!(automatic: true)

        delete "/groups/#{group.id}/members.json", params: { user_id: 42 }
        expect(response.status).to eq(403)
      end

      it "raises an error if user to be removed is not found" do
        delete "/groups/#{group.id}/members.json", params: { user_id: -10 }
        expect(response.status).to eq(404)
      end

      context "is able to remove a member" do
        it "removes by id" do
          expect do
            delete "/groups/#{group.id}/members.json", params: { user_id: user.id }
          end.to change { group.users.count }.by(-1)

          expect(response.status).to eq(200)
        end

        it "removes by username" do
          expect do
            delete "/groups/#{group.id}/members.json", params: { username: user.username }
          end.to change { group.users.count }.by(-1)

          expect(response.status).to eq(200)
        end

        it "removes user.primary_group_id when user is removed from group" do
          user.update!(primary_group_id: group.id)

          delete "/groups/#{group.id}/members.json", params: { user_id: user.id }

          expect(user.reload.primary_group_id).to eq(nil)
        end

        it "removes by user_email" do
          expect do
            delete "/groups/#{group.id}/members.json",
              params: { user_email: user.email }
          end.to change { group.users.count }.by(-1)

          expect(response.status).to eq(200)
        end

        context 'public group' do
          let(:other_user) { Fabricate(:user) }
          let(:group) { Fabricate(:public_group, users: [other_user]) }

          context "admin" do
            it "removes by username" do
              expect do
                delete "/groups/#{group.id}/members.json",
                  params: { username: other_user.username }
              end.to change { group.users.count }.by(-1)

              expect(response.status).to eq(200)
            end
          end

          it 'should allow a user to leave a group' do
            sign_in(other_user)

            expect do
              delete "/groups/#{group.id}/members.json",
              params: { username: other_user.username }
            end.to change { group.users.count }.by(-1)

            expect(response.status).to eq(200)
          end

          it 'should not allow a underprivilege user to leave a group for another user' do
            sign_in(user)

            delete "/groups/#{group.id}/members.json",
              params: { username: other_user.username }

            expect(response).to be_forbidden
          end
        end
      end
    end
  end

  describe "#histories" do
    context 'when user is not signed in' do
      it 'should raise the right error' do
        get "/groups/#{group.name}/logs.json"
        expect(response.status).to eq(403)
      end
    end

    context 'when user is not a group owner' do
      before do
        sign_in(user)
      end

      it 'should be forbidden' do
        get "/groups/#{group.name}/logs.json"

        expect(response).to be_forbidden
      end
    end

    describe 'when user is a group owner' do
      before do
        group.add_owner(user)
        sign_in(user)
      end

      describe 'when viewing a public group' do
        before do
          group.update_attributes!(
            public_admission: true,
            public_exit: true
          )

          GroupActionLogger.new(user, group).log_change_group_settings
        end

        it 'should allow group owner to view history' do
          get "/groups/#{group.name}/logs.json"

          expect(response.status).to eq(200)

          result = JSON.parse(response.body)["logs"].last

          expect(result["action"]).to eq(GroupHistory.actions[1].to_s)
          expect(result["subject"]).to eq('public_exit')
          expect(result["prev_value"]).to eq('f')
          expect(result["new_value"]).to eq('t')
        end
      end

      it 'should not be allowed to view history of an automatic group' do
        group = Group.find_by(id: Group::AUTO_GROUPS[:admins])

        get "/groups/#{group.name}/logs.json"

        expect(response.status).to eq(403)
      end
    end

    context 'when user is an admin' do
      let(:admin) { Fabricate(:admin) }

      before do
        sign_in(admin)
      end

      it 'should be able to view history' do
        GroupActionLogger.new(admin, group).log_remove_user_from_group(user)

        get "/groups/#{group.name}/logs.json"

        expect(response.status).to eq(200)

        result = JSON.parse(response.body)["logs"].first

        expect(result["action"]).to eq(GroupHistory.actions[3].to_s)
      end

      it 'should be able to view history of automatic groups' do
        group = Group.find_by(id: Group::AUTO_GROUPS[:admins])

        get "/groups/#{group.name}/logs.json"

        expect(response.status).to eq(200)
      end

      it 'should be able to filter through the history' do
        GroupActionLogger.new(admin, group).log_add_user_to_group(user)
        GroupActionLogger.new(admin, group).log_remove_user_from_group(user)

        get "/groups/#{group.name}/logs.json", params: {
          filters: { "action" => "add_user_to_group" }
        }

        expect(response.status).to eq(200)

        logs = JSON.parse(response.body)["logs"]

        expect(logs.count).to eq(1)
        expect(logs.first["action"]).to eq(GroupHistory.actions[2].to_s)
      end
    end
  end

  describe '#request_membership' do
    let(:new_user) { Fabricate(:user) }

    it 'requires the user to log in' do
      post "/groups/#{group.name}/request_membership.json"
      expect(response.status).to eq(403)
    end

    it 'requires a reason' do
      sign_in(user)

      post "/groups/#{group.name}/request_membership.json"
      expect(response.status).to eq(400)
    end

    it 'should create the right PM' do
      owner1 = Fabricate(:user, last_seen_at: Time.zone.now)
      owner2 = Fabricate(:user, last_seen_at: Time.zone.now - 1 .day)
      [owner1, owner2].each { |owner| group.add_owner(owner) }

      sign_in(user)

      post "/groups/#{group.name}/request_membership.json",
        params: { reason: 'Please add me in' }

      expect(response.status).to eq(200)

      post = Post.last
      topic = post.topic
      body = JSON.parse(response.body)

      expect(body['relative_url']).to eq(topic.relative_url)
      expect(post.user).to eq(user)

      expect(topic.title).to eq(I18n.t('groups.request_membership_pm.title',
        group_name: group.name
      ))

      expect(post.raw).to eq('Please add me in')
      expect(topic.archetype).to eq(Archetype.private_message)
      expect(topic.allowed_users).to contain_exactly(user, owner1, owner2)
      expect(topic.allowed_groups).to eq([])
    end
  end

  describe '#search ' do
    let(:hidden_group) do
      Fabricate(:group,
        visibility_level: Group.visibility_levels[:owners],
        name: 'KingOfTheNorth'
      )
    end

    before do
      group.update!(
        name: 'GOT',
        full_name: 'Daenerys Targaryen'
      )

      hidden_group
    end

    context 'as an anon user' do
      it "returns the right response" do
        get '/groups/search.json'
        expect(response.status).to eq(403)
      end
    end

    context 'as a normal user' do
      it "returns the right response" do
        sign_in(user)

        get '/groups/search.json'

        expect(response.status).to eq(200)
        groups = JSON.parse(response.body)

        expected_ids = Group::AUTO_GROUPS.map { |name, id| id }
        expected_ids.delete(Group::AUTO_GROUPS[:everyone])
        expected_ids << group.id

        expect(groups.map { |group| group["id"] }).to contain_exactly(*expected_ids)

        ['GO', 'nerys'].each do |term|
          get "/groups/search.json?term=#{term}"

          expect(response.status).to eq(200)
          groups = JSON.parse(response.body)

          expect(groups.length).to eq(1)
          expect(groups.first['id']).to eq(group.id)
        end

        get "/groups/search.json?term=KingOfTheNorth"

        expect(response.status).to eq(200)
        groups = JSON.parse(response.body)

        expect(groups).to eq([])
      end
    end

    context 'as a group owner' do
      before do
        hidden_group.add_owner(user)
      end

      it "returns the right response" do
        sign_in(user)

        get "/groups/search.json?term=north"

        expect(response.status).to eq(200)
        groups = JSON.parse(response.body)

        expect(groups.length).to eq(1)
        expect(groups.first['id']).to eq(hidden_group.id)
      end
    end

    context 'as an admin' do
      it "returns the right response" do
        sign_in(Fabricate(:admin))

        get '/groups/search.json?ignore_automatic=true'

        expect(response.status).to eq(200)
        groups = JSON.parse(response.body)

        expect(groups.length).to eq(2)

        expect(groups.map { |group| group['id'] })
          .to contain_exactly(group.id, hidden_group.id)
      end
    end
  end

  describe '#new' do
    describe 'for an anon user' do
      it 'should return 404' do
        get '/groups/custom/new'

        expect(response.status).to eq(404)
      end
    end

    describe 'for a normal user' do
      before { sign_in(user) }

      it 'should return 404' do
        get '/groups/custom/new'

        expect(response.status).to eq(404)
      end
    end

    describe 'for an admin user' do
      before { sign_in(Fabricate(:admin)) }

      it 'should return 404' do
        get '/groups/custom/new'

        expect(response.status).to eq(200)
      end
    end
  end
end
