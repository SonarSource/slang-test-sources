---
alias: koo7shaino
description: Overview
---

# Overview

<InfoBox>

To understand what Prisma is and how it works, it is crucial that you have a solid understanding of GraphQL and how it is implemented on the server-side. If you're not familiar with the concept of a **GraphQL schema**, its **root types** and the role of **resolver functions**, please make sure to read the following articles:

- [GraphQL Server Basics: The Schema](https://blog.graph.cool/ac5e2950214e)
- [How to build a GraphQL Server](https://blog.graph.cool/6da86f346e68)

Note that if you have used Graphcool to manage your GraphQL server in the past, you will greatly benefit from reading through above articles to get a clearer understanding of Prisma and the value it provides.

</InfoBox>

## From Graphcool Framework to Prisma

The **Graphcool Framework** is the open-source version of the former Graphcool Backend-as-a-Service offering. **Prisma** is a GraphQL query engine and the core technology that is powering the Graphcool Framework.

While the Graphcool Framework offers an opinionated setup for your GraphQL backend and doesn't require you to write a lot of server-side code, Prisma provides more flexibility and leaves many decisions up to the preferences of the developer.

To get an overview of the major differences between Prisma and the Graphcool Framework, read [this](https://www.graph.cool/forum/t/graphcool-framework-and-prisma/2237) forum post.

## Migrating to Prisma

We recommend that you migrate your project in a development environment before putting it into production. This ensures a smooth migration process. You can use the [import](!alias-ol2eoh8xie) and [export](!alias-pa0aip3loh) functionality for Graphcool/Prisma services to migrate data between different projects.

## New architecture: Your GraphQL server uses Prisma as a database layer

The core idea when building GraphQL servers with Prisma is that you have **two GraphQL layers** for your server:

1. The **database layer** is provided by Prisma, this is the core of your server. In essence, the GraphQL API provided by the database layer corresponds to a combined version of the familiar _Simple & Relay APIs_. The API exposes generic and powerful CRUD operations for the types defined in your data model.
2. The **application layer** - this is a new idea if you've only worked with the Graphcool Framework before. The application layer defines another GraphQL API, the one that is exposed to your client applications and tailored to your application's needs.

In that new architecture, the application layer is entirely responsible for business logic and other common workflows such as authentication, permissions or file handling. Rather than writing [Resolver](https://www.graph.cool/docs/reference/functions/resolvers-su6wu3yoo2) or [Hook](https://www.graph.cool/docs/reference/functions/hooks-pa6guruhaf) functions, you are now simply implementing traditional GraphQL resolvers.

Implementing resolvers in your application layer doesn't require a lot of overhead since incoming requests can simply be delegated to the underlying Prisma API using the [`prisma-binding`](!alias-gai5urai6u) package.

## Deployment

Another major difference with Prisma, compared to using the Graphcool Framework, is that you now need to take care of deploying the GraphQL server. One great option for doing so is the Zeit Now one-click deployment tool, you can read a tutorial [here](!alias-ahs1jahkee). Another option is [up](https://up.docs.apex.sh).

Your Prisma service is running on Docker and can be deployed to any cloud provider, such as Digital Ocean or AWS. There also are deployment options based on the Prisma Cloud.
