require 'rails_helper'

describe Invite do

  it { is_expected.to validate_presence_of :invited_by_id }

  it { is_expected.to rate_limit }

  let(:iceking) { 'iceking@adventuretime.ooo' }

  context 'user validators' do
    let(:coding_horror) { Fabricate(:coding_horror) }
    let(:user) { Fabricate(:user) }
    let(:invite) { Invite.create(email: user.email, invited_by: coding_horror) }

    it "should not allow an invite with the same email as an existing user" do
      expect(invite).not_to be_valid
    end

    it "should not allow a user to invite themselves" do
      expect(invite.email_already_exists).to eq(true)
    end

  end

  context 'email validators' do
    let(:coding_horror) { Fabricate(:coding_horror) }
    let(:invite) { Invite.create(email: "test@mailinator.com", invited_by: coding_horror) }

    it "should not allow an invite with blacklisted email" do
      expect(invite).not_to be_valid
    end

    it "should allow an invite with non-blacklisted email" do
      invite = Fabricate(:invite, email: "test@mail.com", invited_by: coding_horror)
      expect(invite).to be_valid
    end

  end

  context '#create' do

    context 'saved' do
      subject { Fabricate(:invite) }

      it "works" do
        expect(subject.invite_key).to be_present
        expect(subject.email_already_exists).to eq(false)
      end

      it 'should store a lower case version of the email' do
        expect(subject.email).to eq(iceking)
      end
    end

    context 'to a topic' do
      let!(:topic) { Fabricate(:topic) }
      let(:inviter) { topic.user }

      context 'email' do
        it 'enqueues a job to email the invite' do
          Jobs.expects(:enqueue).with(:invite_email, has_key(:invite_id))
          topic.invite_by_email(inviter, iceking)
        end
      end

      context 'destroyed' do
        it "can invite the same user after their invite was destroyed" do
          invite = topic.invite_by_email(inviter, iceking)
          invite.destroy
          invite = topic.invite_by_email(inviter, iceking)
          expect(invite).to be_present
        end
      end

      context 'after created' do
        before do
          @invite = topic.invite_by_email(inviter, iceking)
        end

        it 'belongs to the topic' do
          expect(topic.invites).to eq([@invite])
          expect(@invite.topics).to eq([topic])
        end

        context 'when added by another user' do
          let(:coding_horror) { Fabricate(:coding_horror) }
          let(:new_invite) { topic.invite_by_email(coding_horror, iceking) }

          it 'returns a different invite' do
            expect(new_invite).not_to eq(@invite)
            expect(new_invite.invite_key).not_to eq(@invite.invite_key)
            expect(new_invite.topics).to eq([topic])
          end

        end

        context 'when adding a duplicate' do
          it 'returns the original invite' do
            expect(topic.invite_by_email(inviter, 'iceking@adventuretime.ooo')).to eq(@invite)
            expect(topic.invite_by_email(inviter, 'iceking@ADVENTURETIME.ooo')).to eq(@invite)
            expect(topic.invite_by_email(inviter, 'ICEKING@adventuretime.ooo')).to eq(@invite)
          end

          it 'updates timestamp of existing invite' do
            @invite.created_at = 10.days.ago
            @invite.save
            resend_invite = topic.invite_by_email(inviter, 'iceking@adventuretime.ooo')
            expect(resend_invite.created_at).to be_within(1.minute).of(Time.zone.now)
          end

          it 'returns a new invite if the other has expired' do
            SiteSetting.invite_expiry_days = 1
            @invite.created_at = 2.days.ago
            @invite.save
            new_invite = topic.invite_by_email(inviter, 'iceking@adventuretime.ooo')
            expect(new_invite).not_to eq(@invite)
            expect(new_invite).not_to be_expired
          end
        end

        context 'when adding to another topic' do
          let!(:another_topic) { Fabricate(:topic, user: topic.user) }

          it 'should be the same invite' do
            @new_invite = another_topic.invite_by_email(inviter, iceking)
            expect(@new_invite).to eq(@invite)
            expect(another_topic.invites).to eq([@invite])
            expect(@invite.topics).to match_array([topic, another_topic])
          end

        end
      end
    end
  end

  context 'to a group-private topic' do
    let(:group) { Fabricate(:group) }
    let(:private_category)  { Fabricate(:private_category, group: group) }
    let(:group_private_topic) { Fabricate(:topic, category: private_category) }
    let(:inviter) { group_private_topic.user }

    before do
      group.add_owner(inviter)
      @invite = group_private_topic.invite_by_email(inviter, iceking)
    end

    it 'should add the groups to the invite' do
      expect(@invite.groups).to eq([group])
    end

    context 'when duplicated' do
      it 'should not duplicate the groups' do
        expect(group_private_topic.invite_by_email(inviter, iceking)).to eq(@invite)
        expect(@invite.groups).to eq([group])
      end
    end

    it 'verifies that inviter is authorized to invite user to a topic' do
      tl2_user = Fabricate(:user, trust_level: 2)

      invite = group_private_topic.invite_by_email(tl2_user, 'foo@bar.com')
      expect(invite.groups.count).to eq(0)
    end

    context 'automatic groups' do
      it 'should not add invited user to automatic groups' do
        group.update!(automatic: true)
        expect(group_private_topic.invite_by_email(Fabricate(:admin), iceking).groups.count).to eq(0)
      end
    end
  end

  context 'an existing user' do
    let(:topic) { Fabricate(:topic, category_id: nil, archetype: 'private_message') }
    let(:coding_horror) { Fabricate(:coding_horror) }

    it "works" do
      # doesn't create an invite
      expect { topic.invite_by_email(topic.user, coding_horror.email) }.to raise_error(Invite::UserExists)

      # gives the user permission to access the topic
      expect(topic.allowed_users.include?(coding_horror)).to eq(true)
    end

  end

  context 'a staged user' do
    it 'creates an invite for a staged user' do
      Fabricate(:staged, email: 'staged@account.com')
      invite = Invite.invite_by_email('staged@account.com', Fabricate(:coding_horror))

      expect(invite).to be_valid
      expect(invite.email).to eq('staged@account.com')
    end
  end

  context '.redeem' do

    let(:invite) { Fabricate(:invite) }

    it 'creates a notification for the invitee' do
      expect { invite.redeem }.to change(Notification, :count)
    end

    it 'wont redeem an expired invite' do
      SiteSetting.expects(:invite_expiry_days).returns(10)
      invite.update_column(:created_at, 20.days.ago)
      expect(invite.redeem).to be_blank
    end

    it 'wont redeem a deleted invite' do
      invite.destroy
      expect(invite.redeem).to be_blank
    end

    it "won't redeem an invalidated invite" do
      invite.invalidated_at = 1.day.ago
      expect(invite.redeem).to be_blank
    end

    context "deletes duplicate invites" do
      let(:another_user) { Fabricate(:user) }

      it 'delete duplicate invite' do
        another_invite = Fabricate(:invite, email: invite.email, invited_by: another_user)
        invite.redeem
        duplicate_invite = Invite.find_by(id: another_invite.id)
        expect(duplicate_invite).to be_nil
      end

      it 'does not delete already redeemed invite' do
        redeemed_invite = Fabricate(:invite, email: invite.email, invited_by: another_user, redeemed_at: 1.day.ago)
        invite.redeem
        used_invite = Invite.find_by(id: redeemed_invite.id)
        expect(used_invite).not_to be_nil
      end

    end

    context "as a moderator" do
      it "will give the user a moderator flag" do
        invite.invited_by = Fabricate(:admin)
        invite.moderator = true
        invite.save

        user = invite.redeem
        expect(user).to be_moderator
      end

      it "will not give the user a moderator flag if the inviter is not staff" do
        invite.moderator = true
        invite.save

        user = invite.redeem
        expect(user).not_to be_moderator
      end
    end

    context "when inviting to groups" do
      it "add the user to the correct groups" do
        group = Fabricate(:group)
        invite.invited_groups.build(group_id: group.id)
        invite.save

        user = invite.redeem
        expect(user.groups.count).to eq(1)
      end
    end

    context "invite trust levels" do
      it "returns the trust level in default_invitee_trust_level" do
        SiteSetting.default_invitee_trust_level = TrustLevel[3]
        expect(invite.redeem.trust_level).to eq(TrustLevel[3])
      end
    end

    context 'inviting when must_approve_users? is enabled' do
      it 'correctly activates accounts' do
        invite.invited_by = Fabricate(:admin)
        SiteSetting.must_approve_users = true
        user = invite.redeem
        expect(user.approved?).to eq(true)
      end
    end

    context 'simple invite' do

      let!(:user) { invite.redeem }

      it 'works correctly' do
        expect(user.is_a?(User)).to eq(true)
        expect(user.send_welcome_message).to eq(true)
        expect(user.trust_level).to eq(SiteSetting.default_invitee_trust_level)
      end

      context 'after redeeming' do
        before do
          invite.reload
        end

        it 'works correctly' do
          # has set the user_id attribute
          expect(invite.user).to eq(user)

          # returns true for redeemed
          expect(invite).to be_redeemed
        end

        context 'again' do
          it 'will not redeem twice' do
            expect(invite.redeem).to be_blank
          end
        end
      end

    end

    context 'invited to topics' do
      let(:tl2_user) { Fabricate(:user, trust_level: 2) }
      let!(:topic) { Fabricate(:private_message_topic, user: tl2_user) }

      let!(:invite) do
        topic.invite(topic.user, 'jake@adventuretime.ooo')
        Invite.find_by(invited_by_id: topic.user)
      end

      context 'redeem topic invite' do
        it 'adds the user to the topic_users' do
          user = invite.redeem
          topic.reload
          expect(topic.allowed_users.include?(user)).to eq(true)
          expect(Guardian.new(user).can_see?(topic)).to eq(true)
        end
      end

      context 'invited by another user to the same topic' do
        let(:another_tl2_user) { Fabricate(:user, trust_level: 2) }
        let!(:another_invite) { topic.invite(another_tl2_user, 'jake@adventuretime.ooo') }
        let!(:user) { invite.redeem }

        it 'adds the user to the topic_users' do
          topic.reload
          expect(topic.allowed_users.include?(user)).to eq(true)
        end
      end

      context 'invited by another user to a different topic' do
        let!(:user) { invite.redeem }
        let(:another_tl2_user) { Fabricate(:user, trust_level: 2) }
        let(:another_topic) { Fabricate(:topic, user: another_tl2_user) }

        it 'adds the user to the topic_users of the first topic' do
          expect(another_topic.invite(another_tl2_user, user.username)).to be_truthy # invited via username
          expect(topic.allowed_users.include?(user)).to eq(true)
          expect(another_topic.allowed_users.include?(user)).to eq(true)
        end
      end
    end
  end

  describe '.find_all_invites_from' do
    context 'with user that has invited' do
      it 'returns invites' do
        inviter = Fabricate(:user)
        invite = Fabricate(:invite, invited_by: inviter)

        invites = Invite.find_all_invites_from(inviter)

        expect(invites).to include invite
      end
    end

    context 'with user that has not invited' do
      it 'does not return invites' do
        user = Fabricate(:user)
        Fabricate(:invite)

        invites = Invite.find_all_invites_from(user)

        expect(invites).to be_empty
      end
    end
  end

  describe '.find_pending_invites_from' do
    it 'returns pending invites only' do
      inviter = Fabricate(:user)
      Fabricate(
        :invite,
        invited_by: inviter,
        user_id: 123,
        email: 'redeemed@example.com'
      )

      pending_invite = Fabricate(
        :invite,
        invited_by: inviter,
        user_id: nil,
        email: 'pending@example.com'
      )

      invites = Invite.find_pending_invites_from(inviter)

      expect(invites.length).to eq(1)
      expect(invites.first).to eq pending_invite

      expect(Invite.find_pending_invites_count(inviter)).to eq(1)
    end
  end

  describe '.find_redeemed_invites_from' do
    it 'returns redeemed invites only' do
      inviter = Fabricate(:user)
      Fabricate(
        :invite,
        invited_by: inviter,
        user_id: nil,
        email: 'pending@example.com'
      )

      redeemed_invite = Fabricate(
        :invite,
        invited_by: inviter,
        user_id: 123,
        email: 'redeemed@example.com'
      )

      invites = Invite.find_redeemed_invites_from(inviter)

      expect(invites.length).to eq(1)
      expect(invites.first).to eq redeemed_invite

      expect(Invite.find_redeemed_invites_count(inviter)).to eq(1)
    end
  end

  describe '.invalidate_for_email' do
    let(:email) { 'invite.me@example.com' }
    subject { described_class.invalidate_for_email(email) }

    it 'returns nil if there is no invite for the given email' do
      expect(subject).to eq(nil)
    end

    it 'sets the matching invite to be invalid' do
      invite = Fabricate(:invite, invited_by: Fabricate(:user), user_id: nil, email: email)
      expect(subject).to eq(invite)
      expect(subject.link_valid?).to eq(false)
      expect(subject).to be_valid
    end

    it 'sets the matching invite to be invalid without being case-sensitive' do
      invite = Fabricate(:invite, invited_by: Fabricate(:user), user_id: nil, email: 'invite.me2@Example.COM')
      result = described_class.invalidate_for_email('invite.me2@EXAMPLE.com')
      expect(result).to eq(invite)
      expect(result.link_valid?).to eq(false)
      expect(result).to be_valid
    end
  end

  describe '.redeem_from_email' do
    let(:inviter) { Fabricate(:user) }
    let(:invite) { Fabricate(:invite, invited_by: inviter, email: 'test@example.com', user_id: nil) }
    let(:user) { Fabricate(:user, email: invite.email) }

    it 'redeems the invite from email' do
      Invite.redeem_from_email(user.email)
      invite.reload
      expect(invite).to be_redeemed
    end

    it 'does not redeem the invite if email does not match' do
      Invite.redeem_from_email('test24@example.com')
      invite.reload
      expect(invite).not_to be_redeemed
    end

  end

  describe '.rescind_all_invites_from' do
    it 'removes all invites sent by a user' do
      user = Fabricate(:user)
      invite_1 = Fabricate(:invite, invited_by: user)
      invite_2 = Fabricate(:invite, invited_by: user)
      Invite.rescind_all_invites_from(user)
      invite_1.reload
      invite_2.reload
      expect(invite_1.deleted_at).to be_present
      expect(invite_2.deleted_at).to be_present
    end
  end
end
