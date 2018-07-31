require 'tmpdir'

describe Cert do
  describe Cert::Runner do
    before do
      ENV["DELIVER_USER"] = "test@fastlane.tools"
      ENV["DELIVER_PASSWORD"] = "123"
    end

    it "Successful run" do
      certificate = stub_certificate

      allow(Spaceship).to receive(:login).and_return(nil)
      allow(Spaceship).to receive(:client).and_return("client")
      allow(Spaceship).to receive(:select_team).and_return(nil)
      allow(Spaceship.client).to receive(:in_house?).and_return(false)
      allow(Spaceship.certificate.production).to receive(:all).and_return([certificate])

      allow(FastlaneCore::CertChecker).to receive(:installed?).and_return(true)

      options = { keychain_path: "." }
      Cert.config = FastlaneCore::Configuration.create(Cert::Options.available_options, options)

      Cert::Runner.new.launch
      expect(ENV["CER_CERTIFICATE_ID"]).to eq("cert_id")
      expect(ENV["CER_FILE_PATH"]).to eq("#{Dir.pwd}/cert_id.cer")
      File.delete(ENV["CER_FILE_PATH"])
    end

    it "correctly selects expired certificates" do
      expired_cert = stub_certificate(Time.now.utc - 1)
      good_cert = stub_certificate

      allow(Spaceship).to receive(:client).and_return("client")
      allow(Spaceship.client).to receive(:in_house?).and_return(false)
      allow(Spaceship.certificate.production).to receive(:all).and_return([expired_cert, good_cert])

      expect(Cert::Runner.new.expired_certs).to eq([expired_cert])
    end

    it "revokes expired certificates via revoke_expired sub-command" do
      expired_cert = stub_certificate(Time.now.utc - 1)
      good_cert = stub_certificate

      allow(Spaceship).to receive(:login).and_return(nil)
      allow(Spaceship).to receive(:client).and_return("client")
      allow(Spaceship).to receive(:select_team).and_return(nil)
      allow(Spaceship.client).to receive(:in_house?).and_return(false)
      allow(Spaceship.certificate.production).to receive(:all).and_return([expired_cert, good_cert])

      allow(FastlaneCore::CertChecker).to receive(:installed?).and_return(true)

      expect(expired_cert).to receive(:revoke!)
      expect(good_cert).to_not(receive(:revoke!))

      Cert.config = FastlaneCore::Configuration.create(Cert::Options.available_options, keychain_path: ".")
      Cert::Runner.new.revoke_expired_certs!
    end

    it "tries to revoke all expired certificates even if one has an error" do
      expired_cert_1 = stub_certificate(Time.now.utc - 1)
      expired_cert_2 = stub_certificate(Time.now.utc - 1)

      allow(Spaceship).to receive(:login).and_return(nil)
      allow(Spaceship).to receive(:client).and_return("client")
      allow(Spaceship).to receive(:select_team).and_return(nil)
      allow(Spaceship.client).to receive(:in_house?).and_return(false)
      allow(Spaceship.certificate.production).to receive(:all).and_return([expired_cert_1, expired_cert_2])

      allow(FastlaneCore::CertChecker).to receive(:installed?).and_return(true)

      expect(expired_cert_1).to receive(:revoke!).and_raise("Boom!")
      expect(expired_cert_2).to receive(:revoke!)

      Cert.config = FastlaneCore::Configuration.create(Cert::Options.available_options, keychain_path: ".")
      Cert::Runner.new.revoke_expired_certs!
    end

    describe ":filename option handling" do
      filename = ""
      let(:temp) { Dir.tmpdir }
      let(:certificate) { stub_certificate }
      let(:filepath) do
        filename_ext = File.extname(filename) == ".cer" ? filename : "#{filename}.cer"
        File.extname(filename) == ".cer" ? "#{temp}/#{filename}" : "#{temp}/#{filename}.cer"
      end

      before do
        allow(Spaceship).to receive(:login).and_return(nil)
        allow(Spaceship).to receive(:client).and_return("client")
        allow(Spaceship).to receive(:select_team).and_return(nil)
        allow(Spaceship.client).to receive(:in_house?).and_return(false)
        allow(Spaceship.certificate.production).to receive(:all).and_return([certificate])
        allow(FastlaneCore::CertChecker).to receive(:installed?).and_return(true)
      end

      let(:generate) do
        options = { output_path: temp, filename: filename }
        Cert.config = FastlaneCore::Configuration.create(Cert::Options.available_options, options)
        Cert::Runner.new.launch
      end

      context 'with filename flag' do
        it "can forget the file extension" do
          filename = "cert_name"
          expect(File.exist?(filepath)).to be false
          generate
          expect(File.exist?(filepath)).to be true
          File.delete(filepath)
        end

        it "can use the file extension" do
          filename = "cert_name.cer"
          expect(File.exist?(filepath)).to be false
          generate
          expect(File.exist?(filepath)).to be true
          File.delete(filepath)
        end
      end

      context 'without filename flag' do
        it "can generate certificate" do
          filename = "cert_name.cer"
          expect(File.exist?(filepath)).to be false
          generate
          expect(File.exist?(filepath)).to be true
          File.delete(filepath)
        end
      end
    end
  end
end
