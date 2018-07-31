require 'rails_helper'

describe Admin::EmailController do
  let(:admin) { Fabricate(:admin) }
  let(:email_log) { Fabricate(:email_log) }

  before do
    sign_in(admin)
  end

  it "is a subclass of AdminController" do
    expect(Admin::EmailController < Admin::AdminController).to eq(true)
  end

  describe '#index' do
    before do
      Admin::EmailController.any_instance
        .expects(:action_mailer_settings)
        .returns(
          username: 'username',
          password: 'secret'
        )
    end

    it 'does not include the password in the response' do
      get "/admin/email.json"
      mail_settings = JSON.parse(response.body)['settings']

      expect(
        mail_settings.select { |setting| setting['name'] == 'password' }
      ).to be_empty
    end
  end

  describe '#sent' do
    let(:post) { Fabricate(:post) }
    let(:email_log) { Fabricate(:email_log, post: post) }

    let(:post_reply_key) do
      Fabricate(:post_reply_key, post: post, user: email_log.user)
    end

    it "should return the right response" do
      email_log
      get "/admin/email/sent.json"

      expect(response.status).to eq(200)
      log = JSON.parse(response.body).first
      expect(log["id"]).to eq(email_log.id)
      expect(log["reply_key"]).to eq(nil)

      post_reply_key

      get "/admin/email/sent.json"

      expect(response.status).to eq(200)
      log = JSON.parse(response.body).first
      expect(log["id"]).to eq(email_log.id)
      expect(log["reply_key"]).to eq(post_reply_key.reply_key)
    end
  end

  describe '#skipped' do
    let(:user) { Fabricate(:user) }
    let!(:log1) { Fabricate(:skipped_email_log, user: user) }
    let!(:log2) { Fabricate(:skipped_email_log) }

    it "succeeds" do
      get "/admin/email/skipped.json"

      expect(response.status).to eq(200)

      logs = JSON.parse(response.body)

      expect(logs.first["id"]).to eq(log2.id)
      expect(logs.last["id"]).to eq(log1.id)
    end

    describe 'when filtered by username' do
      it 'should return the right response' do
        get "/admin/email/skipped.json", params: {
          user: user.username
        }

        expect(response.status).to eq(200)

        logs = JSON.parse(response.body)

        expect(logs.count).to eq(1)
        expect(logs.first["id"]).to eq(log1.id)
      end
    end
  end

  describe '#test' do
    it 'raises an error without the email parameter' do
      post "/admin/email/test.json"
      expect(response.status).to eq(400)
    end

    context 'with an email address' do
      it 'enqueues a test email job' do
        post "/admin/email/test.json", params: { email_address: 'eviltrout@test.domain' }

        expect(response.status).to eq(200)
        expect(ActionMailer::Base.deliveries.map(&:to).flatten).to include('eviltrout@test.domain')
      end
    end
  end

  describe '#preview_digest' do
    it 'raises an error without the last_seen_at parameter' do
      get "/admin/email/preview-digest.json"
      expect(response.status).to eq(400)
    end

    it "previews the digest" do
      get "/admin/email/preview-digest.json", params: {
        last_seen_at: 1.week.ago, username: admin.username
      }
      expect(response.status).to eq(200)
    end
  end

  describe '#handle_mail' do
    it 'should enqueue the right job' do
      expect { post "/admin/email/handle_mail.json", params: { email: email('cc') } }
        .to change { Jobs::ProcessEmail.jobs.count }.by(1)
      expect(response.status).to eq(200)
    end
  end

  describe '#rejected' do
    it 'should provide a string for a blank error' do
      Fabricate(:incoming_email, error: "")
      get "/admin/email/rejected.json"
      expect(response.status).to eq(200)
      rejected = JSON.parse(response.body)
      expect(rejected.first['error']).to eq(I18n.t("emails.incoming.unrecognized_error"))
    end
  end

  describe '#incoming' do
    it 'should provide a string for a blank error' do
      incoming_email = Fabricate(:incoming_email, error: "")
      get "/admin/email/incoming/#{incoming_email.id}.json"
      expect(response.status).to eq(200)
      incoming = JSON.parse(response.body)
      expect(incoming['error']).to eq(I18n.t("emails.incoming.unrecognized_error"))
    end
  end
end
