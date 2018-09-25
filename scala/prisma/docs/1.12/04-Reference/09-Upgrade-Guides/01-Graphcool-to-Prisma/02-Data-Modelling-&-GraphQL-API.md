---
alias: eiwuezeec3
description: GraphQL API
---

# Data Modelling & GraphQL API

Prisma introduces a few changes to the way how your data model is written as well as to the generated GraphQL API.

## Data modelling

### Remove `@model` directive

The `@model` directive that was previously required to denote your model types is removed.

#### Before

```graphql
type User @model {
  id: ID! @isUnique
  name: String!
}
```

#### After

```graphql
type User {
  id: ID! @unique
  name: String!
}
```

### `@relation` directive becomes optional on unambiguous relations

When a relation in your data model is unambiguous, you can omit the `@relation` directive.

#### Before

```graphql
type User @model {
  id: ID! @isUnique
  name: String!
  posts: [Post!]! @relation(name: "UsersPosts")
}

type Post @model {
  id: ID! @isUnique
  title: String!
  author: User! @relation(name: "UsersPosts")
}
```

#### After

```graphql
type User @model {
  id: ID! @unique
  name: String!
  posts: [Post!]!
}

type Post @model {
  id: ID! @unique
  title: String!
  author: User!
}
```

### `id` field is optional

The `id` field is now optional on the model types in your data model (similar to `createdAt` and `updatedAt`), you can remove it if it's not needed on a type.

### `@isUnique` is renamed to `@unique`

The `@isUnique` directive is renamed to `@unique`.

#### Before

```graphql
type User {
  id: ID! @isUnique
  email: String! @isUnique
}
```

#### After

```graphql
type User {
  id: ID! @unique
  email: String! @unique
}
```

### `@defaultValue` is renamed to `@default`

The `@defaultValue` directive is renamed to `@default`.

#### Before

```graphql
type User {
  id: ID! @isUnique
  name: String! @defaultValue(value: "Unknown")
}
```

#### After

```graphql
type User {
  id: ID! @unique
  name: String! @default(value: "Unknown")
}
```

## GraphQL API

### Unified API: Merging Simple & Relay APIs

Most notably of all API changes, Prisma merges the previous Simple and Relay APIs. The resulting API is compatible with all GraphQL clients. Consequently, each Prisma service only provides a single HTTP endpoint.

### Wrapped input arguments

The Simple API of the Graphcool Framework followed the approach of passing single values to mutations. In the new Prisma API, all input arguments for mutations are wrapped in one `data` argument.

#### Before

```graphql
mutation {
  createPost(title: "GraphQL is great" text: "It really is") {
    id
  }
}
```

#### After

```graphql
mutation {
  createPost(data: {
    title: "GraphQL is great"
    text: "It really is"
  }) {
    id
  }
}
```

### Removing `all`-prefix from query root fields

The generated queries to return lists of nodes have the `all`-prefix removed.

#### Before

```graphql
query {
  allUsers {
    id
    name
  }
}
```

#### After

```graphql
query {
  users {
    id
    name
  }
}
```

### Lowercasing query root fields asking for single nodes

When asking for a single node, the corresponding query is now lowercased.

```graphql
query {
  User(id: "cjd5pqjuzpbuy0171tiuj098t") {
    id
    name
  }
}
```

#### After

```graphql
query {
  user(id: "cjd5pqjuzpbuy0171tiuj098t") {
    id
    name
  }
}
```

### `filter` renamed to `where`

The `filter` argument has been renamed to `where` in the Prisma GraphQL API. \

#### Before

```graphql
query {
  allUsers(filter: {
    name_contains: "Karl"
  }) {
    id
    name
  }
}
```

#### After

```graphql
query {
  users(where: {
    name_contains: "Karl"
  }) {
    id
    name
  }
}
```

### Selecting nodes by any `@unique` field

In the Graphcool Framework GraphQL API, it was only possible to update and delete nodes by selecting them via their `id` field. With Prisma, you can use any field that's annotated with the `@unique` directive for that.

Consider this data model:

```graphql
type User {
  id: ID! @unique
  email: String! @unique
}
```

With Prisma, you can now send the following mutation to delete a `User` node:

```graphql
mutation {
  deleteUser(by: {
    email: "alice@graph.cool"
  }) {
    id
  }
}
```

## New API features

New API features introduced in Prisma include [batch operations](!alias-utee3eiquo#batch-mutations), [improved nested mutations](!alias-utee3eiquo#nested-mutations), [transactional mutations](!alias-utee3eiquo#transactional-mutations) and more.

This means that for these use cases, you can now use these new primitives instead of following a more complex setup as before.
