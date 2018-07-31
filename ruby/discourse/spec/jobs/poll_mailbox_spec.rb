require 'rails_helper'
require_dependency 'jobs/regular/process_post'

describe Jobs::PollMailbox do

  let(:poller) { Jobs::PollMailbox.new }

  describe ".execute" do

    it "does no polling if pop3_polling_enabled is false" do
      SiteSetting.expects(:pop3_polling_enabled).returns(false)
      poller.expects(:poll_pop3).never
      poller.execute({})
    end

    it "polls when pop3_polling_enabled is true" do
      SiteSetting.expects(:pop3_polling_enabled).returns(true)
      poller.expects(:poll_pop3).once
      poller.execute({})
    end

  end

  describe ".poll_pop3" do

    context "pop errors" do
      let(:user) { Fabricate(:user) }

      before do
        Discourse.expects(:handle_job_exception).at_least_once
      end

      after do
        $redis.del(Jobs::PollMailbox::POLL_MAILBOX_TIMEOUT_ERROR_KEY)
      end

      it "add an admin dashboard message on pop authentication error" do
        Net::POP3.any_instance.expects(:start)
          .raises(Net::POPAuthenticationError.new).at_least_once

        poller.poll_pop3

        i18n_key = 'dashboard.poll_pop3_auth_error'

        expect(AdminDashboardData.problem_message_check(i18n_key))
          .to eq(I18n.t(i18n_key))
      end

      it "logs an error on pop connection timeout error" do
        Net::POP3.any_instance.expects(:start).raises(Net::OpenTimeout.new).at_least_once

        4.times { poller.poll_pop3 }

        i18n_key = 'dashboard.poll_pop3_timeout'

        expect(AdminDashboardData.problem_message_check(i18n_key))
          .to eq(I18n.t(i18n_key))
      end
    end

    it "calls enable_ssl when the setting is enabled" do
      SiteSetting.pop3_polling_ssl = true
      Net::POP3.any_instance.stubs(:start)
      Net::POP3.any_instance.expects(:enable_ssl)
      poller.poll_pop3
    end

    it "does not call enable_ssl when the setting is disabled" do
      SiteSetting.pop3_polling_ssl = false
      Net::POP3.any_instance.stubs(:start)
      Net::POP3.any_instance.expects(:enable_ssl).never
      poller.poll_pop3
    end

    context "has emails" do
      before do
        mail1 = Net::POPMail.new(3, nil, nil, nil)
        mail2 = Net::POPMail.new(3, nil, nil, nil)
        mail3 = Net::POPMail.new(3, nil, nil, nil)
        Net::POP3.any_instance.stubs(:start).yields(Net::POP3.new(nil, nil))
        Net::POP3.any_instance.stubs(:mails).returns([mail1, mail2, mail3])
        Net::POP3.any_instance.expects(:delete_all).never
        poller.stubs(:process_popmail)
      end

      it "deletes emails from server when when deleting emails from server is enabled" do
        Net::POPMail.any_instance.stubs(:delete).times(3)
        SiteSetting.pop3_polling_delete_from_server = true
        poller.poll_pop3
      end

      it "does not delete emails server inbox when deleting emails from server is disabled" do
        Net::POPMail.any_instance.stubs(:delete).never
        SiteSetting.pop3_polling_delete_from_server = false
        poller.poll_pop3
      end
    end
  end

  describe "#process_popmail" do
    def process_popmail(email_name)
      pop_mail = stub("pop mail")
      pop_mail.expects(:pop).returns(email(email_name))
      Jobs::PollMailbox.new.process_popmail(pop_mail)
    end

    it "does not reply to a bounced email" do
      expect { process_popmail(:bounced_email) }.to_not change { ActionMailer::Base.deliveries.count }

      incoming_email = IncomingEmail.last

      expect(incoming_email.rejection_message).to eq(
        I18n.t("emails.incoming.errors.bounced_email_error")
      )
    end

  end

end
