describe Fastlane do
  describe Fastlane::FastFile do
    describe "adb_devices" do
      it "generates a valid command" do
        result = Fastlane::FastFile.new.parse("lane :test do
          adb_devices(adb_path: '/some/path/to/adb')
        end").runner.execute(:test)

        expect(result).to match_array([])
      end

      it "generates a valid command when ANDROID_SDK_ROOT is set" do
        result = Fastlane::FastFile.new.parse("lane :test do
          ENV['ANDROID_SDK_ROOT'] = '/usr/local/android-sdk'
          adb_devices()
        end").runner.execute(:test)

        expect(result).to match_array([])
      end
    end
  end
end
