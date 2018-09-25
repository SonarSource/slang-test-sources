---
alias: aiqu3eicae
description: Upgrade to 1.8
---

# Upgrade to 1.8

## Overview

The [1.8 release](https://github.com/graphcool/prisma/releases/tag/1.8.0) introduces a couple of changes surrounding deployment and setting up and managing Prisma servers.

## Changes to the Management API

The Management API of a Prisma Server is responsible for service deployment and also provides information about the Prisma Server.

Previously, the Management API was available at `/cluster`, for example `localhost:4466/cluster`.
With 1.8, `/cluster` is not available anymore. The endpoint has been updated to `/management`, for example `localhost:4466/management`.

The Management API exposes a GraphQL API, that previously contained a top-level field `clusterInfo`. It has been renamed to `serverInfo` in 1.8. `clusterInfo` is deprecated and will be removed in version 1.10.

## Changes to the management database

A Prisma Server stores information about services and service migrations in a dedicated management schema in the connected database. Previously, this schema was called `graphcool`. In 1.8, `management` is used by default instead. Using the `managementSchema` setting in the Prisma Server configuration allows you to use a different database. For the Postgres connector, both the `database` and the `managementSchema` setting can be adjusted.

Using MySQL:

```yml
version: '3'
services:
  prisma:
    image: prismagraphql/prisma:1.8
    restart: always
    ports:
    - "4466:4466"
    environment:
      PRISMA_CONFIG: |
        port: 4466
        databases:
          default:
            connector: mysql
            host: https://example.com
            port: 3306
            user: root
            password: prisma
            managementSchema: graphcool # default: management
```

Using Postgres:

```yml
version: '3'
services:
  prisma:
    image: prismagraphql/prisma:1.8
    restart: always
    ports:
    - "4466:4466"
    environment:
      PRISMA_CONFIG: |
        port: 4466
        databases:
          default:
            connector: postgres
            host: https://example.com
            port: 5432
            user: root
            password: prisma
            database: graphcool # default: prisma
            managementSchema: graphcool # default: management
```

## A new migrations setting

In anticipation of connectors with disabled migrations, a new connector setting `migrations` is available:


```yml
version: '3'
services:
  prisma:
    image: prismagraphql/prisma:1.8
    restart: always
    ports:
    - "4466:4466"
    environment:
      PRISMA_CONFIG: |
        port: 4466
        databases:
          default:
            connector: postgres
            host: https://example.com
            port: 5432
            user: root
            password: prisma
            migrations: false # default: true
```

The setting `migrations: false` is typically used with existing databases that come with a preconfigured database schema and existing data.
This is usually used in combination with the new [introspection feature](!alias-aeb6diethe).

With 1.8, we are introducing preliminary alpha support for the Postgres connector to connect to existing databases. The same will be possible with the MySQL connector further down the road.
