require 'rails_helper'

describe UserUpdater do

  let(:acting_user) { Fabricate.build(:user) }

  describe '#update_muted_users' do
    it 'has no cross talk' do
      u1 = Fabricate(:user)
      u2 = Fabricate(:user)
      u3 = Fabricate(:user)

      updater = UserUpdater.new(u1, u1)
      updater.update_muted_users("#{u2.username},#{u3.username}")

      updater = UserUpdater.new(u2, u2)
      updater.update_muted_users("#{u3.username},#{u1.username}")

      updater = UserUpdater.new(u3, u3)
      updater.update_muted_users("")

      expect(MutedUser.where(user_id: u2.id).count).to eq 2
      expect(MutedUser.where(user_id: u1.id).count).to eq 2
      expect(MutedUser.where(user_id: u3.id).count).to eq 0

    end
  end

  describe '#update' do
    let(:category) { Fabricate(:category) }
    let(:tag) { Fabricate(:tag) }
    let(:tag2) { Fabricate(:tag) }

    it 'saves user' do
      user = Fabricate(:user, name: 'Billy Bob')
      updater = UserUpdater.new(acting_user, user)

      updater.update(name: 'Jim Tom')

      expect(user.reload.name).to eq 'Jim Tom'
    end

    it 'can update categories and tags' do
      user = Fabricate(:user)
      updater = UserUpdater.new(acting_user, user)
      updater.update(watched_tags: "#{tag.name},#{tag2.name}", muted_category_ids: [category.id])

      expect(TagUser.where(
        user_id: user.id,
        tag_id: tag.id,
        notification_level: TagUser.notification_levels[:watching]
      ).exists?).to eq(true)

      expect(TagUser.where(
        user_id: user.id,
        tag_id: tag2.id,
        notification_level: TagUser.notification_levels[:watching]
      ).exists?).to eq(true)

      expect(CategoryUser.where(
        user_id: user.id,
        category_id: category.id,
        notification_level: CategoryUser.notification_levels[:muted]
      ).count).to eq(1)

    end

    it "doesn't remove notification prefs when updating something else" do
      user = Fabricate(:user)
      TagUser.create!(user: user, tag: tag, notification_level: TagUser.notification_levels[:watching])
      CategoryUser.create!(user: user, category: category, notification_level: CategoryUser.notification_levels[:muted])

      updater = UserUpdater.new(acting_user, user)
      updater.update(name: "Steve Dave")

      expect(TagUser.where(user: user).count).to eq(1)
      expect(CategoryUser.where(user: user).count).to eq(1)
    end

    it 'updates various fields' do
      user = Fabricate(:user)
      updater = UserUpdater.new(acting_user, user)
      date_of_birth = Time.zone.now

      theme = Theme.create!(user_id: -1, name: "test", user_selectable: true)

      seq = user.user_option.theme_key_seq

      val = updater.update(bio_raw: 'my new bio',
                           email_always: 'true',
                           mailing_list_mode: true,
                           digest_after_minutes: "45",
                           new_topic_duration_minutes: 100,
                           auto_track_topics_after_msecs: 101,
                           notification_level_when_replying: 3,
                           email_in_reply_to: false,
                           date_of_birth: date_of_birth,
                           theme_ids: [theme.id],
                           allow_private_messages: false)

      expect(val).to be_truthy

      user.reload

      expect(user.user_profile.bio_raw).to eq 'my new bio'
      expect(user.user_option.email_always).to eq true
      expect(user.user_option.mailing_list_mode).to eq true
      expect(user.user_option.digest_after_minutes).to eq 45
      expect(user.user_option.new_topic_duration_minutes).to eq 100
      expect(user.user_option.auto_track_topics_after_msecs).to eq 101
      expect(user.user_option.notification_level_when_replying).to eq 3
      expect(user.user_option.email_in_reply_to).to eq false
      expect(user.user_option.theme_ids.first).to eq theme.id
      expect(user.user_option.theme_key_seq).to eq(seq + 1)
      expect(user.user_option.allow_private_messages).to eq(false)
      expect(user.date_of_birth).to eq(date_of_birth.to_date)
    end

    it "disables email_digests when enabling mailing_list_mode" do
      user = Fabricate(:user)
      updater = UserUpdater.new(acting_user, user)

      val = updater.update(mailing_list_mode: true, email_digests: true)
      expect(val).to be_truthy

      user.reload

      expect(user.user_option.email_digests).to eq false
      expect(user.user_option.mailing_list_mode).to eq true
    end

    context 'when sso overrides bio' do
      it 'does not change bio' do
        SiteSetting.sso_url = "https://www.example.com/sso"
        SiteSetting.enable_sso = true
        SiteSetting.sso_overrides_bio = true

        user = Fabricate(:user)
        updater = UserUpdater.new(acting_user, user)

        expect(updater.update(bio_raw: "new bio")).to be_truthy

        user.reload
        expect(user.user_profile.bio_raw).not_to eq 'new bio'
      end
    end

    context 'when update fails' do
      it 'returns false' do
        user = Fabricate(:user)
        user.stubs(save: false)
        updater = UserUpdater.new(acting_user, user)

        expect(updater.update).to be_falsey
      end
    end

    context 'with permission to update title' do
      it 'allows user to change title' do
        user = Fabricate(:user, title: 'Emperor')
        guardian = stub
        guardian.stubs(:can_grant_title?).with(user, 'Minion').returns(true)
        Guardian.stubs(:new).with(acting_user).returns(guardian)
        updater = UserUpdater.new(acting_user, user)

        updater.update(title: 'Minion')

        expect(user.reload.title).to eq 'Minion'
      end
    end

    context 'title is from a badge' do
      let(:user) { Fabricate(:user, title: 'Emperor') }
      let(:badge) { Fabricate(:badge, name: 'Minion') }

      context 'badge can be used as a title' do
        before do
          badge.update_attributes(allow_title: true)
        end

        it 'can use as title, sets badge_granted_title' do
          BadgeGranter.grant(badge, user)
          updater = UserUpdater.new(user, user)
          updater.update(title: badge.name)
          user.reload
          expect(user.user_profile.badge_granted_title).to eq(true)
        end

        it 'badge has not been granted, does not change title' do
          badge.update_attributes(allow_title: true)
          updater = UserUpdater.new(user, user)
          updater.update(title: badge.name)
          user.reload
          expect(user.title).not_to eq(badge.name)
          expect(user.user_profile.badge_granted_title).to eq(false)
        end

        it 'changing to a title that is not from a badge, unsets badge_granted_title' do
          user.update_attributes(title: badge.name)
          user.user_profile.update_attributes(badge_granted_title: true)

          guardian = stub
          guardian.stubs(:can_grant_title?).with(user, 'Dancer').returns(true)
          Guardian.stubs(:new).with(user).returns(guardian)

          updater = UserUpdater.new(user, user)
          updater.update(title: 'Dancer')
          user.reload
          expect(user.title).to eq('Dancer')
          expect(user.user_profile.badge_granted_title).to eq(false)
        end
      end

      it 'cannot use as title, does not change title' do
        BadgeGranter.grant(badge, user)
        updater = UserUpdater.new(user, user)
        updater.update(title: badge.name)
        user.reload
        expect(user.title).not_to eq(badge.name)
        expect(user.user_profile.badge_granted_title).to eq(false)
      end
    end

    context 'without permission to update title' do
      it 'does not allow user to change title' do
        user = Fabricate(:user, title: 'Emperor')
        guardian = stub
        guardian.stubs(:can_grant_title?).with(user, 'Minion').returns(false)
        Guardian.stubs(:new).with(acting_user).returns(guardian)
        updater = UserUpdater.new(acting_user, user)

        updater.update(title: 'Minion')

        expect(user.reload.title).not_to eq 'Minion'
      end
    end

    context 'when website includes http' do
      it 'does not add http before updating' do
        user = Fabricate(:user)
        updater = UserUpdater.new(acting_user, user)

        updater.update(website: 'http://example.com')

        expect(user.reload.user_profile.website).to eq 'http://example.com'
      end
    end

    context 'when website does not include http' do
      it 'adds http before updating' do
        user = Fabricate(:user)
        updater = UserUpdater.new(acting_user, user)

        updater.update(website: 'example.com')

        expect(user.reload.user_profile.website).to eq 'http://example.com'
      end
    end

    context 'when custom_fields is empty string' do
      it "update is successful" do
        user = Fabricate(:user)
        user.custom_fields = { 'import_username' => 'my_old_username' }
        user.save
        updater = UserUpdater.new(acting_user, user)

        updater.update(website: 'example.com', custom_fields: '')
        expect(user.reload.custom_fields).to eq('import_username' => 'my_old_username')
      end
    end

    it "logs the action" do
      user_without_name = Fabricate(:user, name: nil)
      user = Fabricate(:user, name: 'Billy Bob')
      expect do
        UserUpdater.new(acting_user, user).update(name: 'Jim Tom')
      end.to change { UserHistory.count }.by(1)

      expect do
        UserUpdater.new(acting_user, user).update(name: 'JiM TOm')
      end.to_not change { UserHistory.count }

      expect do
        UserUpdater.new(acting_user, user_without_name).update(bio_raw: 'foo bar')
      end.to_not change { UserHistory.count }

      expect do
        UserUpdater.new(acting_user, user_without_name).update(name: 'Jim Tom')
      end.to change { UserHistory.count }.by(1)

      expect do
        UserUpdater.new(acting_user, user).update(name: '')
      end.to change { UserHistory.count }.by(1)
    end
  end
end
