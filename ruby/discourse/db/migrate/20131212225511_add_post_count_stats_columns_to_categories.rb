class AddPostCountStatsColumnsToCategories < ActiveRecord::Migration[4.2]
  def change
    change_table :categories do |t|
      t.integer :posts_year
      t.integer :posts_month
      t.integer :posts_week
    end
  end
end
