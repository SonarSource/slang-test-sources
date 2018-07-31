Fabricator(:queued_post) do
  queue 'test'
  state QueuedPost.states[:new]
  user
  topic
  raw 'This post should be queued up'
  post_options do
    { reply_to_post_number: 1,
      via_email: true,
      raw_email: 'store_me',
      auto_track: true,
      custom_fields: { hello: 'world' },
      cooking_options: { cat: 'hat' },
      cook_method: Post.cook_methods[:raw_html],
      image_sizes: { "http://foo.bar/image.png" => { "width" => 0, "height" => 222 } } }
  end
end

Fabricator(:queued_topic, from: :queued_post) do
  topic nil
  raw 'This post should be queued up, and more importantly, this is a new topic'
  post_options do
    { category: 1,
      title: 'This is a new topic' }
  end
end
