**DO NOT READ THIS FILE ON GITHUB, GUIDES ARE PUBLISHED ON https://guides.rubyonrails.org.**

Action Cable Overview
=====================

In this guide, you will learn how Action Cable works and how to use WebSockets to
incorporate real-time features into your Rails application.

After reading this guide, you will know:

* What Action Cable is and its integration backend and frontend
* How to setup Action Cable
* How to setup channels
* Deployment and Architecture setup for running Action Cable

--------------------------------------------------------------------------------

Introduction
------------

Action Cable seamlessly integrates
[WebSockets](https://en.wikipedia.org/wiki/WebSocket) with the rest of your
Rails application. It allows for real-time features to be written in Ruby in the
same style and form as the rest of your Rails application, while still being
performant and scalable. It's a full-stack offering that provides both a
client-side JavaScript framework and a server-side Ruby framework. You have
access to your full domain model written with Active Record or your ORM of
choice.

What is Pub/Sub
---------------

[Pub/Sub](https://en.wikipedia.org/wiki/Publish%E2%80%93subscribe_pattern), or
Publish-Subscribe, refers to a message queue paradigm whereby senders of
information (publishers), send data to an abstract class of recipients
(subscribers), without specifying individual recipients. Action Cable uses this
approach to communicate between the server and many clients.

## Server-Side Components

### Connections

*Connections* form the foundation of the client-server relationship. For every
WebSocket accepted by the server, a connection object is instantiated. This
object becomes the parent of all the *channel subscriptions* that are created
from there on. The connection itself does not deal with any specific application
logic beyond authentication and authorization. The client of a WebSocket
connection is called the connection *consumer*. An individual user will create
one consumer-connection pair per browser tab, window, or device they have open.

Connections are instances of `ApplicationCable::Connection`. In this class, you
authorize the incoming connection, and proceed to establish it if the user can
be identified.

#### Connection Setup

```ruby
# app/channels/application_cable/connection.rb
module ApplicationCable
  class Connection < ActionCable::Connection::Base
    identified_by :current_user

    def connect
      self.current_user = find_verified_user
    end

    private
      def find_verified_user
        if verified_user = User.find_by(id: cookies.encrypted[:user_id])
          verified_user
        else
          reject_unauthorized_connection
        end
      end
  end
end
```

Here `identified_by` is a connection identifier that can be used to find the
specific connection later. Note that anything marked as an identifier will automatically
create a delegate by the same name on any channel instances created off the connection.

This example relies on the fact that you will already have handled authentication of the user
somewhere else in your application, and that a successful authentication sets a signed
cookie with the user ID.

The cookie is then automatically sent to the connection instance when a new connection
is attempted, and you use that to set the `current_user`. By identifying the connection
by this same current user, you're also ensuring that you can later retrieve all open
connections by a given user (and potentially disconnect them all if the user is deleted
or unauthorized).

### Channels

A *channel* encapsulates a logical unit of work, similar to what a controller does in a
regular MVC setup. By default, Rails creates a parent `ApplicationCable::Channel` class
for encapsulating shared logic between your channels.

#### Parent Channel Setup

```ruby
# app/channels/application_cable/channel.rb
module ApplicationCable
  class Channel < ActionCable::Channel::Base
  end
end
```

Then you would create your own channel classes. For example, you could have a
`ChatChannel` and an `AppearanceChannel`:

```ruby
# app/channels/chat_channel.rb
class ChatChannel < ApplicationCable::Channel
end

# app/channels/appearance_channel.rb
class AppearanceChannel < ApplicationCable::Channel
end
```

A consumer could then be subscribed to either or both of these channels.

#### Subscriptions

Consumers subscribe to channels, acting as *subscribers*. Their connection is
called a *subscription*. Produced messages are then routed to these channel
subscriptions based on an identifier sent by the cable consumer.

```ruby
# app/channels/chat_channel.rb
class ChatChannel < ApplicationCable::Channel
  # Called when the consumer has successfully
  # become a subscriber to this channel.
  def subscribed
  end
end
```

## Client-Side Components

### Connections

Consumers require an instance of the connection on their side. This can be
established using the following JavaScript, which is generated by default by Rails:

#### Connect Consumer

```js
// app/assets/javascripts/cable.js
//= require action_cable
//= require_self
//= require_tree ./channels

(function() {
  this.App || (this.App = {});

  App.cable = ActionCable.createConsumer();
}).call(this);
```

This will ready a consumer that'll connect against `/cable` on your server by default.
The connection won't be established until you've also specified at least one subscription
you're interested in having.

#### Subscriber

A consumer becomes a subscriber by creating a subscription to a given channel:

```coffeescript
# app/assets/javascripts/cable/subscriptions/chat.coffee
App.cable.subscriptions.create { channel: "ChatChannel", room: "Best Room" }

# app/assets/javascripts/cable/subscriptions/appearance.coffee
App.cable.subscriptions.create { channel: "AppearanceChannel" }
```

While this creates the subscription, the functionality needed to respond to
received data will be described later on.

A consumer can act as a subscriber to a given channel any number of times. For
example, a consumer could subscribe to multiple chat rooms at the same time:

```coffeescript
App.cable.subscriptions.create { channel: "ChatChannel", room: "1st Room" }
App.cable.subscriptions.create { channel: "ChatChannel", room: "2nd Room" }
```

## Client-Server Interactions

### Streams

*Streams* provide the mechanism by which channels route published content
(broadcasts) to their subscribers.

```ruby
# app/channels/chat_channel.rb
class ChatChannel < ApplicationCable::Channel
  def subscribed
    stream_from "chat_#{params[:room]}"
  end
end
```

If you have a stream that is related to a model, then the broadcasting used
can be generated from the model and channel. The following example would
subscribe to a broadcasting like `comments:Z2lkOi8vVGVzdEFwcC9Qb3N0LzE`

```ruby
class CommentsChannel < ApplicationCable::Channel
  def subscribed
    post = Post.find(params[:id])
    stream_for post
  end
end
```

You can then broadcast to this channel like this:

```ruby
CommentsChannel.broadcast_to(@post, @comment)
```

### Broadcasting

A *broadcasting* is a pub/sub link where anything transmitted by a publisher
is routed directly to the channel subscribers who are streaming that named
broadcasting. Each channel can be streaming zero or more broadcastings.

Broadcastings are purely an online queue and time-dependent. If a consumer is
not streaming (subscribed to a given channel), they'll not get the broadcast
should they connect later.

Broadcasts are called elsewhere in your Rails application:

```ruby
WebNotificationsChannel.broadcast_to(
  current_user,
  title: 'New things!',
  body: 'All the news fit to print'
)
```

The `WebNotificationsChannel.broadcast_to` call places a message in the current
subscription adapter (by default `redis` for production and `async` for development and
test environments)'s pubsub queue under a separate broadcasting name for each user.
For a user with an ID of 1, the broadcasting name would be `web_notifications:1`.

The channel has been instructed to stream everything that arrives at
`web_notifications:1` directly to the client by invoking the `received`
callback.

### Subscriptions

When a consumer is subscribed to a channel, they act as a subscriber. This
connection is called a subscription. Incoming messages are then routed to
these channel subscriptions based on an identifier sent by the cable consumer.

```coffeescript
# app/assets/javascripts/cable/subscriptions/chat.coffee
# Assumes you've already requested the right to send web notifications
App.cable.subscriptions.create { channel: "ChatChannel", room: "Best Room" },
  received: (data) ->
    @appendLine(data)

  appendLine: (data) ->
    html = @createLine(data)
    $("[data-chat-room='Best Room']").append(html)

  createLine: (data) ->
    """
    <article class="chat-line">
      <span class="speaker">#{data["sent_by"]}</span>
      <span class="body">#{data["body"]}</span>
    </article>
    """
```

### Passing Parameters to Channels

You can pass parameters from the client side to the server side when creating a
subscription. For example:

```ruby
# app/channels/chat_channel.rb
class ChatChannel < ApplicationCable::Channel
  def subscribed
    stream_from "chat_#{params[:room]}"
  end
end
```

An object passed as the first argument to `subscriptions.create` becomes the
params hash in the cable channel. The keyword `channel` is required:

```coffeescript
# app/assets/javascripts/cable/subscriptions/chat.coffee
App.cable.subscriptions.create { channel: "ChatChannel", room: "Best Room" },
  received: (data) ->
    @appendLine(data)

  appendLine: (data) ->
    html = @createLine(data)
    $("[data-chat-room='Best Room']").append(html)

  createLine: (data) ->
    """
    <article class="chat-line">
      <span class="speaker">#{data["sent_by"]}</span>
      <span class="body">#{data["body"]}</span>
    </article>
    """
```

```ruby
# Somewhere in your app this is called, perhaps
# from a NewCommentJob.
ActionCable.server.broadcast(
  "chat_#{room}",
  sent_by: 'Paul',
  body: 'This is a cool chat app.'
)
```

### Rebroadcasting a Message

A common use case is to *rebroadcast* a message sent by one client to any
other connected clients.

```ruby
# app/channels/chat_channel.rb
class ChatChannel < ApplicationCable::Channel
  def subscribed
    stream_from "chat_#{params[:room]}"
  end

  def receive(data)
    ActionCable.server.broadcast("chat_#{params[:room]}", data)
  end
end
```

```coffeescript
# app/assets/javascripts/cable/subscriptions/chat.coffee
App.chatChannel = App.cable.subscriptions.create { channel: "ChatChannel", room: "Best Room" },
  received: (data) ->
    # data => { sent_by: "Paul", body: "This is a cool chat app." }

App.chatChannel.send({ sent_by: "Paul", body: "This is a cool chat app." })
```

The rebroadcast will be received by all connected clients, _including_ the
client that sent the message. Note that params are the same as they were when
you subscribed to the channel.

## Full-Stack Examples

The following setup steps are common to both examples:

  1. [Setup your connection](#connection-setup).
  2. [Setup your parent channel](#parent-channel-setup).
  3. [Connect your consumer](#connect-consumer).

### Example 1: User Appearances

Here's a simple example of a channel that tracks whether a user is online or not
and what page they're on. (This is useful for creating presence features like showing
a green dot next to a user name if they're online).

Create the server-side appearance channel:

```ruby
# app/channels/appearance_channel.rb
class AppearanceChannel < ApplicationCable::Channel
  def subscribed
    current_user.appear
  end

  def unsubscribed
    current_user.disappear
  end

  def appear(data)
    current_user.appear(on: data['appearing_on'])
  end

  def away
    current_user.away
  end
end
```

When a subscription is initiated the `subscribed` callback gets fired and we
take that opportunity to say "the current user has indeed appeared". That
appear/disappear API could be backed by Redis, a database, or whatever else.

Create the client-side appearance channel subscription:

```coffeescript
# app/assets/javascripts/cable/subscriptions/appearance.coffee
App.cable.subscriptions.create "AppearanceChannel",
  # Called when the subscription is ready for use on the server.
  connected: ->
    @install()
    @appear()

  # Called when the WebSocket connection is closed.
  disconnected: ->
    @uninstall()

  # Called when the subscription is rejected by the server.
  rejected: ->
    @uninstall()

  appear: ->
    # Calls `AppearanceChannel#appear(data)` on the server.
    @perform("appear", appearing_on: $("main").data("appearing-on"))

  away: ->
    # Calls `AppearanceChannel#away` on the server.
    @perform("away")


  buttonSelector = "[data-behavior~=appear_away]"

  install: ->
    $(document).on "turbolinks:load.appearance", =>
      @appear()

    $(document).on "click.appearance", buttonSelector, =>
      @away()
      false

    $(buttonSelector).show()

  uninstall: ->
    $(document).off(".appearance")
    $(buttonSelector).hide()
```

##### Client-Server Interaction

1. **Client** connects to the **Server** via `App.cable =
ActionCable.createConsumer("ws://cable.example.com")`. (`cable.js`). The
**Server** identifies this connection by `current_user`.

2. **Client** subscribes to the appearance channel via
`App.cable.subscriptions.create(channel: "AppearanceChannel")`. (`appearance.coffee`)

3. **Server** recognizes a new subscription has been initiated for the
appearance channel and runs its `subscribed` callback, calling the `appear`
method on `current_user`. (`appearance_channel.rb`)

4. **Client** recognizes that a subscription has been established and calls
`connected` (`appearance.coffee`) which in turn calls `@install` and `@appear`.
`@appear` calls `AppearanceChannel#appear(data)` on the server, and supplies a
data hash of `{ appearing_on: $("main").data("appearing-on") }`. This is
possible because the server-side channel instance automatically exposes all
public methods declared on the class (minus the callbacks), so that these can be
reached as remote procedure calls via a subscription's `perform` method.

5. **Server** receives the request for the `appear` action on the appearance
channel for the connection identified by `current_user`
(`appearance_channel.rb`). **Server** retrieves the data with the
`:appearing_on` key from the data hash and sets it as the value for the `:on`
key being passed to `current_user.appear`.

### Example 2: Receiving New Web Notifications

The appearance example was all about exposing server functionality to
client-side invocation over the WebSocket connection. But the great thing
about WebSockets is that it's a two-way street. So now let's show an example
where the server invokes an action on the client.

This is a web notification channel that allows you to trigger client-side
web notifications when you broadcast to the right streams:

Create the server-side web notifications channel:

```ruby
# app/channels/web_notifications_channel.rb
class WebNotificationsChannel < ApplicationCable::Channel
  def subscribed
    stream_for current_user
  end
end
```

Create the client-side web notifications channel subscription:

```coffeescript
# app/assets/javascripts/cable/subscriptions/web_notifications.coffee
# Client-side which assumes you've already requested
# the right to send web notifications.
App.cable.subscriptions.create "WebNotificationsChannel",
  received: (data) ->
    new Notification data["title"], body: data["body"]
```

Broadcast content to a web notification channel instance from elsewhere in your
application:

```ruby
# Somewhere in your app this is called, perhaps from a NewCommentJob
WebNotificationsChannel.broadcast_to(
  current_user,
  title: 'New things!',
  body: 'All the news fit to print'
)
```

The `WebNotificationsChannel.broadcast_to` call places a message in the current
subscription adapter's pubsub queue under a separate broadcasting name for each
user. For a user with an ID of 1, the broadcasting name would be
`web_notifications:1`.

The channel has been instructed to stream everything that arrives at
`web_notifications:1` directly to the client by invoking the `received`
callback. The data passed as argument is the hash sent as the second parameter
to the server-side broadcast call, JSON encoded for the trip across the wire
and unpacked for the data argument arriving as `received`.

### More Complete Examples

See the [rails/actioncable-examples](https://github.com/rails/actioncable-examples)
repository for a full example of how to setup Action Cable in a Rails app and adding channels.

## Configuration

Action Cable has two required configurations: a subscription adapter and allowed request origins.

### Subscription Adapter

By default, Action Cable looks for a configuration file in `config/cable.yml`.
The file must specify an adapter for each Rails environment. See the
[Dependencies](#dependencies) section for additional information on adapters.

```yaml
development:
  adapter: async

test:
  adapter: async

production:
  adapter: redis
  url: redis://10.10.3.153:6381
  channel_prefix: appname_production
```
#### Adapter Configuration

Below is a list of the subscription adapters available for end users.

##### Async Adapter

The async adapter is intended for development/testing and should not be used in production.

##### Redis Adapter

The Redis adapter requires users to provide a URL pointing to the Redis server.
Additionally, a `channel_prefix` may be provided to avoid channel name collisions
when using the same Redis server for multiple applications. See the [Redis PubSub documentation](https://redis.io/topics/pubsub#database-amp-scoping) for more details.

##### PostgreSQL Adapter

The PostgreSQL adapter uses Active Record's connection pool, and thus the
application's `config/database.yml` database configuration, for its connection.
This may change in the future. [#27214](https://github.com/rails/rails/issues/27214)

### Allowed Request Origins

Action Cable will only accept requests from specified origins, which are
passed to the server config as an array. The origins can be instances of
strings or regular expressions, against which a check for the match will be performed.

```ruby
config.action_cable.allowed_request_origins = ['http://rubyonrails.com', %r{http://ruby.*}]
```

To disable and allow requests from any origin:

```ruby
config.action_cable.disable_request_forgery_protection = true
```

By default, Action Cable allows all requests from localhost:3000 when running
in the development environment.

### Consumer Configuration

To configure the URL, add a call to `action_cable_meta_tag` in your HTML layout
HEAD. This uses a URL or path typically set via `config.action_cable.url` in the
environment configuration files.

### Other Configurations

The other common option to configure is the log tags applied to the
per-connection logger. Here's an example that uses
the user account id if available, else "no-account" while tagging:

```ruby
config.action_cable.log_tags = [
  -> request { request.env['user_account_id'] || "no-account" },
  :action_cable,
  -> request { request.uuid }
]
```

For a full list of all configuration options, see the
`ActionCable::Server::Configuration` class.

Also, note that your server must provide at least the same number of database
connections as you have workers. The default worker pool size is set to 4, so
that means you have to make at least that available. You can change that in
`config/database.yml` through the `pool` attribute.

## Running Standalone Cable Servers

### In App

Action Cable can run alongside your Rails application. For example, to
listen for WebSocket requests on `/websocket`, specify that path to
`config.action_cable.mount_path`:

```ruby
# config/application.rb
class Application < Rails::Application
  config.action_cable.mount_path = '/websocket'
end
```

You can use `App.cable = ActionCable.createConsumer()` to connect to the cable
server if `action_cable_meta_tag` is invoked in the layout. A custom path is
specified as first argument to `createConsumer` (e.g. `App.cable =
ActionCable.createConsumer("/websocket")`).

For every instance of your server you create and for every worker your server
spawns, you will also have a new instance of Action Cable, but the use of Redis
keeps messages synced across connections.

### Standalone

The cable servers can be separated from your normal application server. It's
still a Rack application, but it is its own Rack application. The recommended
basic setup is as follows:

```ruby
# cable/config.ru
require_relative '../config/environment'
Rails.application.eager_load!

run ActionCable.server
```

Then you start the server using a binstub in `bin/cable` ala:

```
#!/bin/bash
bundle exec puma -p 28080 cable/config.ru
```

The above will start a cable server on port 28080.

### Notes

The WebSocket server doesn't have access to the session, but it has
access to the cookies. This can be used when you need to handle
authentication. You can see one way of doing that with Devise in this [article](http://www.rubytutorial.io/actioncable-devise-authentication).

## Dependencies

Action Cable provides a subscription adapter interface to process its
pubsub internals. By default, asynchronous, inline, PostgreSQL, and Redis
adapters are included. The default adapter
in new Rails applications is the asynchronous (`async`) adapter.

The Ruby side of things is built on top of [websocket-driver](https://github.com/faye/websocket-driver-ruby),
[nio4r](https://github.com/celluloid/nio4r), and [concurrent-ruby](https://github.com/ruby-concurrency/concurrent-ruby).

## Deployment

Action Cable is powered by a combination of WebSockets and threads. Both the
framework plumbing and user-specified channel work are handled internally by
utilizing Ruby's native thread support. This means you can use all your regular
Rails models with no problem, as long as you haven't committed any thread-safety sins.

The Action Cable server implements the Rack socket hijacking API,
thereby allowing the use of a multithreaded pattern for managing connections
internally, irrespective of whether the application server is multi-threaded or not.

Accordingly, Action Cable works with popular servers like Unicorn, Puma, and
Passenger.
