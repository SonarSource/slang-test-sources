---
alias: aey0vohche
description: Use subscriptions to receive data updates in realtime.
---

# Subscriptions

## Overview

_GraphQL subscriptions_ allow you to be notified in realtime when changes are happening to your data. There are three kinds of _events_ that trigger a subscription:

- A new node is **created**
- An existing node is **updated**
- An existing node is **deleted**

This is an example subscription that notifies you whenever a new `Post` node is created. When the subscription fires, the payload that's sent by the server will contain the `description` and `imageUrl` of the `Post`:

```graphql
subscription newPosts {
  post(where: {
    mutation_in: [CREATED]
  }) {
    mutation
    node {
      description
      imageUrl
    }
  }
}
```

Subscriptions use a special websocket endpoint.

Here's a list of available subscriptions. To explore them, use the [GraphQL Playground](https://github.com/graphcool/graphql-playground) inside your service.

- For every [object type](!alias-eiroozae8u#object-types) in your data model, a [type subscription](#type-subscriptions) is available to listen for data changes on that type.
- Currently, connecting or disconnecting nodes in a [relation](!alias-eiroozae8u#relations) does not trigger a subscription! You can find more info in this [GitHub issue](https://github.com/graphcool/framework/issues/146) and read below for a [workaround](#relation-subscriptions).

You can [combine multiple subscription triggers](#combining-subscriptions) within a single subscription request to control exactly what events you want to be notified of. The subscriptions API also uses the rich filter system that's available for [queries](!alias-ahwee4zaey).

## Subscription requests

When using [Apollo Client](https://www.apollographql.com/client/), you can use the [`apollo-link-ws`](https://github.com/apollographql/apollo-link-ws) library to facilitate subscription use. [Here's an example](https://github.com/graphcool-examples/react-graphql/tree/master/subscriptions-with-apollo-worldchat).

You can also use the [GraphQL Playground](https://github.com/graphcool/graphql-playground) or any WebSocket client as described below.

### Playground

A [GraphQL Playground](https://github.com/graphcool/graphql-playground) can be used to explore and run GraphQL subscriptions.

### Plain WebSockets

#### 1. Establish connection

Subscriptions are managed through WebSockets. First establish a WebSocket connection and specify the [`graphql-subscriptions`](https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md) protocol:

```js
let webSocket = new WebSocket('wss://__CLUSTER__.prisma.sh/__WORKSPACE__/__SERVICE__/__STAGE__', 'graphql-subscriptions');
```

#### 2. Initiate handshake

Next you need to initiate a handshake with the WebSocket server. You do this by listening to the `open` event and then sending a JSON message to the server with the `type` property set to `init`:

```js
webSocket.onopen = (event) => {
  const message = {
      type: 'init'
  }

  webSocket.send(JSON.stringify(message))
}
```

#### 3. Receive messages

The server may respond with a variety of messages distinguished by their `type` property. You can react to each message as appropriate for your application:

```js
webSocket.onmessage = (event) => {
  const data = JSON.parse(event.data)

  switch (data.type) {
    case 'init_success': {
      console.log('init_success, the handshake is complete')
      break
    }
    case 'init_fail': {
      throw {
        message: 'init_fail returned from WebSocket server',
        data
      }
    }
    case 'subscription_data': {
      console.log('subscription data has been received', data)
      break
    }
    case 'subscription_success': {
      console.log('subscription_success')
      break
    }
    case 'subscription_fail': {
      throw {
        message: 'subscription_fail returned from WebSocket server',
        data
      }
    }
  }
}
```

#### 4. Subscribe to data changes

To subscribe to data changes, send a message with the `type` property set to `subscription_start`:

```js
const message = {
  id: '1',
  type: 'subscription_start',
  query: `
    subscription newPosts {
      post(filter: {
        mutation_in: [CREATED]
      }) {
        mutation
        node {
          description
          imageUrl
        }
      }
    }
  `
}

webSocket.send(JSON.stringify(message))
```

You should receive a message with `type` set to `subscription_success`. When data changes occur, you will receive messages with `type` set to `subscription_data`. The `id` property that you supply in the `subscription_start` message will appear on all `subscription_data` messages, allowing you to multiplex your WebSocket connection.

#### 5. Unsubscribe from data changes

To unsubscribe from data changes, send a message with the `type` property set to `subscription_end`:

```js
const message = {
  id: '1',
  type: 'subscription_end'
}

webSocket.send(JSON.stringify(message))
```

## Type subscriptions

For every available [object type](!alias-eiroozae8u#object-types) in your data model, certain subscriptions are automatically generated.

As an example, consider the following data model with a single `Post` type:

```graphql
type Post {
  id: ID! @unique
  title: String!
  description: String
}
```

In the generated [Prisma API](!alias-ohm2ouceuj), a `post` subscription will be available that you can use to be notified whenever certain nodes of type `Post` are [created](#subscribing-to-created-nodes), [updated](#subscribing-to-updated-nodes) or [deleted](#subscribing-to-deleted-nodes).

### Subscribing to created nodes

For a given type, you can subscribe to all nodes that are being created using the generated type subscription.

#### Subscribe to all created nodes

If you want to subscribe to created nodes of the `Post` type, you can use the `Post` subscription and specify the `where` object and set `mutation_in: [CREATED]`.

```graphql
subscription {
  post(where: {
    mutation_in: [CREATED]
  }) {
    mutation
    node {
      description
      imageUrl
      author {
        id
      }
    }
  }
}
```

The payload contains:

- `mutation`: In this case it will return `CREATED`.
- `node`: Allows you to query information on the created node (and potentially its related nodes).

#### Subscribe to specific created nodes

You can make use of a similar [filter system as for queries](!alias-nia9nushae#filtering-by-field) using the `node` argument of the `where` object.

For example, to only be notified of a created `Post` if a specific user _follows_ the `author`:

```graphql
subscription {
  post(where: {
    AND: [{
      mutation_in: [CREATED]
    }, {
    node: {
      author: {
        followedBy_some: {
          id: "cj03x3nacox6m0119755kmcm3"
        }
      }
    }]
  }) {
    mutation
    node {
      description
      imageUrl
      author {
        id
      }
    }
  }
}
```

### Subscribing to deleted nodes

For a given type, you can subscribe to all nodes that are being deleted using the generated type subscription.

#### Subscribe to all deleted nodes

If you want to subscribe for updated nodes of the `Post` type, you can use the `Post` subscription and specify the `where` object and set `mutation_in: [DELETED]`.

```graphql
subscription deletePost {
  post(where: {
    mutation_in: [DELETED]
  }) {
    mutation
    previousValues {
      id
    }
  }
}
```

The payload contains

- `mutation`: In this case it will return `DELETED`.
- `previousValues`: Previous scalar values of the node.

> **Note**: `previousValues` is always `null` for `CREATED` subscriptions.

#### Subscribe to specific deleted nodes

You can make use of a similar [filter system as for queries](!alias-nia9nushae#filtering-by-field) using the `node` argument of the `where` object.

For example, to only be notified of a deleted `Post` if a specific user follows the `author`:

```graphql
subscription {
  post(where: {
    mutation_in: [DELETED]
    node: {
      author: {
        followedBy_some: {
          id: "cj03x3nacox6m0119755kmcm3"
        }
      }
    }
  }) {
    mutation
    previousValues {
      id
    }
  }
}
```

### Subscribing to updated nodes

For a given type, you can subscribe to all nodes being updated using the generated type subscription.

#### Subscribe to all updated nodes

If you want to subscribe to updated nodes of the `Post` type, you can use the `Post` subscription and specify the `where` object and set `mutation_in: [UPDATED]`.

```graphql
subscription {
  post(where: {
    mutation_in: [UPDATED]
  }) {
    mutation
    node {
      description
      imageUrl
      author {
        id
      }
    }
    updatedFields
    previousValues {
      description
      imageUrl
    }
  }
}
```

The payload contains

- `mutation`: In this case it will return `UPDATED`.
- `node`: Allows you to query information on the updated node and connected nodes.
- `updatedFields`: A list of the fields that changed.
- `previousValues`: Previous scalar values of the node.

> **Note**: `updatedFields` is always `null` for `CREATED` and `DELETED` subscriptions. `previousValues` is always `null` for `CREATED` subscriptions.

#### Subscribe to updated fields

You can make use of a similar [filter system as for queries](!alias-nia9nushae#filtering-by-field) using the `node` argument of the `where` object.

For example, to only be notified of an updated `Post` if its `description` changed:

```graphql
subscription {
  post(where: {
    mutation_in: [UPDATED]
    updatedFields_contains: "description"
  }) {
    mutation
    node {
      description
    }
    updatedFields
    previousValues {
      description
    }
  }
}
```

Similarly to `updatedFields_contains`, more filter conditions exist:

- `updatedFields_contains_every: [String!]`: Matches if all fields specified have been updated.
- `updatedFields_contains_some: [String!]`: Matches if some of the specified fields have been updated.

> **Note**: You cannot use the `updatedFields` filter conditions together with `mutation_in: [CREATED]` or `mutation_in: [DELETED]`!

## Relation subscriptions

Currently, subscriptions for relation updates are only available with a workaround using `UPDATED` subscriptions.

### Subscribing to relation changes

You can force a notification changes by _touching_ nodes. Add a `dummy: String` field to the type in question and update this field for the node whose relation status just changed.

```graphql
mutation updatePost {
  updatePost(
    where: {
      id: "some-id"
    }
    data: {
      dummy: "dummy" # do a dummy change to trigger update subscription
    }
  )
}
```

> If you're interested in a direct relation trigger for subscriptions, [please join the discussion on GitHub](https://github.com/graphcool/feature-requests/issues/146).

## Combining subscriptions

You can subscribe to multiple mutations on the same type in one subscription.

### Subscribe to all changes to all nodes

Using the `mutation_in` argument of the `where` object, you can select the type of mutation that you want to subscribe to. For example, to subscribe to the `createPost`, `updatePost` and `deletePost` mutations:

```graphql
subscription {
  post(where: {
    mutation_in: [CREATED, UPDATED, DELETED]
  }) {
    mutation
    node {
      id
      description
    }
    updatedFields
    previousValues {
      description
      imageUrl
    }
  }
}
```

### Subscribe to all changes to specific nodes

To select specific nodes that you want to be notified about, use the `node` argument of the `where` object. You can combine it with `mutation_in`. For example, to only be notified of created, updated and deleted posts if a specific user follows the author:

```graphql
subscription {
  post(
    where: {
      mutation_in: [CREATED, UPDATED, DELETED]
    }
    node: {
      author: {
        followedBy_some: {
          id: "cj03x3nacox6m0119755kmcm3"
        }
      }
    }
  ) {
    mutation
    node {
      id
      description
    }
    updatedFields
    previousValues {
      description
      imageUrl
    }
  }
}
```

> **Note**: `previousValues` is always `null` for `CREATED` subscriptions and `updatedFields` is always `null` for `CREATED` and `DELETED` subscriptions.

### Advanced subscription filters

You can make use of a similar [filter system as for queries](!alias-nia9nushae#filtering-by-field) using the `where` argument.

For example, you can subscribe to all `CREATED` and `DELETE` subscriptions, as well as all `UPDATED` subscriptions when the `imageUrl` was updated:

```graphql
subscription {
  post(where: {
    OR: [{
      mutation_in: [CREATED, DELETED]
    }, {
      mutation_in: [UPDATED]
      updatedFields_contains: "imageUrl"
    }]
  }) {
    mutation
    node {
      id
      description
    }
    updatedFields
    previousValues {
      description
      imageUrl
    }
  }
}
```

> **Note**: Using any of the `updatedFields` filter conditions together with `CREATED` or `DELETED` subscriptions results in an error. `previousValues` is always `null` for `CREATED` subscriptions and `updatedFields` is always `null` for `CREATED` and `DELETED` subscriptions.
