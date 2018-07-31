require "rails_helper"

describe HasSearchData do
  context "belongs to its model" do
    before do
      DB.exec("create temporary table model_items(id SERIAL primary key)")
      DB.exec("create temporary table model_item_search_data(model_item_id int primary key, search_data tsvector, raw_data text, locale text)")

      class ModelItem < ActiveRecord::Base
        has_one :model_item_search_data, dependent: :destroy
      end

      class ModelItemSearchData < ActiveRecord::Base
        include HasSearchData
      end
    end

    after do
      DB.exec("drop table model_items")
      DB.exec("drop table model_item_search_data")

      # import is making my life hard, we need to nuke this out of orbit
      des = ActiveSupport::DescendantsTracker.class_variable_get :@@direct_descendants
      des[ActiveRecord::Base].delete(ModelItem)
      des[ActiveRecord::Base].delete(ModelItemSearchData)
    end

    let(:item) do
      item = ModelItem.create!
      item.create_model_item_search_data!(
        model_item_id: item.id,
        search_data: 'a',
        raw_data: 'a',
        locale: 'en')
      item
    end

    it 'sets its primary key into associated model' do
      expect(ModelItemSearchData.primary_key).to eq 'model_item_id'
    end

    it 'can access the model' do
      record_id = item.id
      expect(ModelItemSearchData.find_by(model_item_id: record_id).model_item_id).to eq record_id
    end
  end
end
