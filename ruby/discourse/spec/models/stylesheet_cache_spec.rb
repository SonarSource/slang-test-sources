require 'rails_helper'

describe StylesheetCache do

  describe "add" do
    it "correctly cycles once MAX_TO_KEEP is hit" do
      StylesheetCache.destroy_all

      (StylesheetCache::MAX_TO_KEEP + 1).times do |i|
        StylesheetCache.add("a", "d" + i.to_s, "c" + i.to_s, "map")
      end

      expect(StylesheetCache.count).to eq StylesheetCache::MAX_TO_KEEP
      expect(StylesheetCache.order(:id).first.content).to eq "c1"
    end

    it "does nothing if digest is set and already exists" do
      StylesheetCache.delete_all

      expect(StylesheetCache.add("a", "b", "c", "map")).to be_present
      expect(StylesheetCache.add("a", "b", "cc", "map")).to eq(false)

      expect(StylesheetCache.count).to eq 1
      expect(StylesheetCache.first.content).to eq "c"
    end

  end
end
