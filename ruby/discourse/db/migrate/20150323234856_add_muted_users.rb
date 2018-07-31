class AddMutedUsers < ActiveRecord::Migration[4.2]
  def change
    create_table :muted_users, force: true do |t|
      t.integer :user_id, null: false
      t.integer :muted_user_id, null: false
      t.timestamps null: false
    end

    add_index :muted_users, [:user_id, :muted_user_id], unique: true
    add_index :muted_users, [:muted_user_id, :user_id], unique: true
  end
end
