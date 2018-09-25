---
alias: iquaecuj6b
description: Upgrade to 1.7
---

# Upgrade to 1.7

## Overview

The [1.7 release](https://github.com/graphcool/prisma/releases/tag/1.7.0) of Prisma introduces a few major changes for the deployment process of a Prisma API. These changes mainly concern the service configuration within [`prisma.yml`](!alias-ufeshusai8) and a few commands of the Prisma CLI.

All changes are **backwards-compatible**, meaning there is no necessity to incorporate the changes right away. In general, the CLI will help you perform the required changes automatically where possible.

There are two main cases for how the CLI is helping you with the migration:

- **Your API is deployed to a [Prisma Cloud](https://www.prisma.io/cloud) server**: The CLI _automatically_ adjusts `prisma.yml` and writes the new `endpoint` property into it (while removing `service`, `stage` and `cluster`).
- **Your API is _NOT_ deployed to a [Prisma Cloud](https://www.prisma.io/cloud) server**: The CLI prints a warning and provides hints how to perform the updates (see below for more info).

## Terminology

- **Prisma Clusters** are renamed to **Prisma Servers**
- **Development Clusters** are renamed to **Prisma Sandbox**

## Service configuration in `prisma.yml`

### New YAML structure

The service configuration inside `prisma.yml` is based on a new YAML structure (find the updated docs [here](https://www.prisma.io/docs/reference/service-configuration/prisma.yml/yaml-structure-ufeshusai8)):

- The `service`, `stage` and `cluster` properties have been removed.
- A new property called `endpoint` has been added. The new `endpoint` effectively encodes the information of the three removed properties.
- A new property called `post-deploy` has been added (see [Post deployment hooks](#post-deployment-hooks) for more info).
- The `disableAuth` property has been removed. If you don't want your Prisma API to require authentication, simply omit the `secret` property.
- The `schema` property has been removed. Note that the Prisma CLI will not by default download the GraphQL schema (commonly called `prisma.graphql`) for your Prisma API any more! If you want to get access to the GraphQL schema of your Prisma API, you need to configure a [post deploment hook](#post-deployment-hooks) accordingly.

#### Example: Local deployment

Consider this **outdated** version of `prisma.yml`:

```yml
service: myservice
stage: dev
cluster: local

datamodel: datamodel.graphql
```

After migrated to **Prisma 1.7**, the file will have the following structure:

```yml
endpoint: http://localhost:4466/myservice/dev
datamodel: datamodel.graphql
```

### Example: Deploying to a Prisma Sandbox in the Cloud

Consider this **outdated** version of `prisma.yml`:

```yml
service: myservice
stage: dev
cluster: public-crocusraccoon-3/prisma-eu1

datamodel: datamodel.graphql
```

After migrated to **Prisma 1.7**, the file will have the following structure:

```yml
endpoint: https://eu1.prisma.sh/public-crocusraccoon-3/myservice/dev
datamodel: datamodel.graphql
```

### Introducing `default` service name and `default` stage

For convenience, two special values for the _service name_ and _stage_ parts of the Prisma `endpoint` have been introduced. Both values are called `default`. If not explicitly provided, the CLI will automatically infer them.

Concretely, this means that whenever the _service name_ and _stage_ are called `default`, you can omit them in the `endpoint` property of `prisma.yml`.

For example:

- `http://localhost:4466/default/default` can be written as `http://localhost:4466/`
- `https://eu1.prisma.sh/public-helixgoose-752/default/default` can be written as `https://eu1.prisma.sh/public-helixgoose-752/`

This is also relevant for the `/import` and `/export` endpoints of your API.

For example:

- `http://localhost:4466/default/default/import` can be written as `http://localhost:4466/import`
- `https://eu1.prisma.sh/public-helixgoose-752/default/default/export` can be written as `https://eu1.prisma.sh/public-helixgoose-752/export`

### Post deployment hooks

In Prisma 1.7, you can specify arbitrary terminal commands to be executed by the Prisma CLI after a deployment (i.e. after `prisma deploy` has terminated).

Here is an example that performs three tasks after a deployment:

1. Print "Deployment finished"
1. Download the GraphQL schema for the `db` project specified in `.graphqlconfig.yml`
1. Invoke code generation as specified in `.graphqlconfig.yml`

```yml
# in database/prisma.yml
hooks:
  post-deploy:
    - echo "Deployment finished"
    - graphql get-schema
    - graphql prepare
```

## Prisma CLI

### Deprecating `local` commands

The `prisma local` commands are being deprecated in favor of using Docker commands directly. `prisma local` provided a convenient abstraction for certain Docker workflows. In 1.7, everything related to these Docker worfklows can be done manually using the [Docker CLI](https://docs.docker.com/engine/reference/commandline/cli/).

When running `prisma init` in Prisma 1.7, the CLI generates a `docker-compose.yml` file that specifies the images for two Docker containers:

- `prisma`: This is the image for the Prisma API that turns your database into a GraphQL API.
- `db`: This is the image for the connected database, e.g. `mysql`.

Here's what the raw version of this generated `docker-compose.yml` file:

```yml
version: '3'
services:
  prisma:
    image: prismagraphql/prisma:1.7
    restart: always
    ports:
    - "4466:4466"
    environment:
      PRISMA_CONFIG: |
        managementApiSecret: my-server-secret-123
        port: 4466
        databases:
          default:
            connector: mysql  # or `postgres`
            active: true
            host: db
            port: 3306        # or `5432` for `postgres`
            user: root
            password: prisma
  db:
    container_name: prisma-db
    image: mysql:5.7
    restart: always
    environment:
      MYSQL_USER: root
      MYSQL_ROOT_PASSWORD: prisma
```

> **Note**: You can learn more about the different properties of the `docker-compose.yml` file in the [reference](!alias-aira9zama5) or directly in the [Docker documentation](https://docs.docker.com/compose/compose-file/).

### Authenticating against Prisma servers running on Docker

When using the Prisma CLI to deploy and manage your Prisma APIs against a Docker-based [Prisma server](!alias-eu2ood0she), the CLI needs to authenticate its interactions (otherwise anyone with access to the endpoint of the server would be able to arbitrarily modify your Prisma APIs).

In previous Prisma versions, the CLI used an _asymmetric_ authentication approach based on a public/private-keypair. The public key was deployed along with the Prisma cluster and the private key was stored in the _cluster registry_ as the `clusterSecret`. This `clusterSecret` was used by the CLI to authenticate its requests.

With Prisma 1.7, a _symmetric_ authentication approach is introduced. This means the key stored on the deployed Prisma server is identical to the key used by the CLI.

#### Providing the key to the Prisma server

Prisma servers running on Docker receive their keys via the `managementApiSecret` key in `docker-compose.yml`. When deploying the Prisma server using `docker-compose up -d`, the key will be stored on the server. Every request made by the CLI (e.g. `prisma deploy`) now needs to be authenticated with that key.

Here is an example where the `managementApiSecret` key is set to `my-server-secret-123`:

```yml
version: '3'
services:
  prisma:
    image: prismagraphql/prisma:1.7
    restart: always
    ports:
    - "4466:4466"
    environment:
      PRISMA_CONFIG: |
        managementApiSecret: my-server-secret-123
        port: 4466
        databases:
          default:
            connector: mysql  # or `postgres`
            active: true
            host: db
            port: 3306        # or `5432` for `postgres`
            user: root
            password: prisma
  db:
    container_name: prisma-db
    image: mysql:5.7
    restart: always
    environment:
      MYSQL_USER: root
      MYSQL_ROOT_PASSWORD: prisma
```

#### Authenticating requests made by the Prisma CLI

Whenever the CLI makes requests against the server (e.g. `prisma deploy`), it needs to authenticate using the same key that was stored on the Prisma server. But where does it get the key from?

You need to explicitly set the key using the `PRISMA_MANAGEMENT_API_SECRET` environment variable. The easiest way to do so is by using a [`.env`](https://www.npmjs.com/package/dotenv)-file which is automatically "understood" by the Prisma CLI.

Here is an example for a `.env`-file which defines the `my-server-secret-123` key as the `PRISMA_MANAGEMENT_API_SECRET` environment variable. This will allow the Prisma CLI to authenticate against the Prisma server it is talking to:

```
PRISMA_MANAGEMENT_API_SECRET="my-server-secret-123"
```

### Download the Prisma GraphQL schema and invoking codegen

In Prisma 1.7, the Prisma CLI is not automatically taking care of downloading the GraphQL schema (commonly called `prisma.graphql`) for the deployed Prisma API after a deployment any more. If you need the file in your project, you need to download it via a [post deployment hook](#post-deployment-hook).

Similarly, if you're using code generation that is configured via [GraphQL Config](https://github.com/graphcool/graphql-config), you also need to explicitly invoke `graphql prepare` for the code being generated.

Here is the example of a `.graphqlconfig.yml` file that specifies that:

- the Prisma GraphQL schema should be stored in a file called `generated/prisma.graphql`
- the corresponding TypeScript type definitions should be written to a file called `generated/prisma.ts`

```yml
projects:
  prisma:
    schemaPath: generated/prisma.graphql
    extensions:
      prisma: prisma.yml
      prepare-binding:
        output: generated/prisma.ts
        generator: prisma-ts
```

The corresponding post deployment hook in `prisma.yml` for downloading the schema and invoking the code generation needs to be configured as follows:

```yml
hooks:
  post-deploy:
    - graphql get-schema --project prisma
    - graphql prepare
```

### Bootstrap a GraphQL server

Note that in Prisma 1.7, the `--boilerplate` option has been removed from the `prisma init` command. This means you can not bootstrap an entire GraphQL server based on a [GraphQL boilerplate](https://github.com/graphql-boilerplates) project any more.

To bootstrap a GraphQL server based on a GraphQL boilerplate project, use the `graphql create` command from the [GraphQL CLI](https://github.com/graphql-cli/graphql-cli):

```bash
# Install the GraphQL CLI
npm install -g graphql-cli

# Choose a boilerplate from the interactive prompt ...
graphql create myapp 

# ...or directly select a boilerplate project via the `--boilerplate` option (e.g. `typescript-advanced`)
graphql create myapp --boilerplate typescript-advanced
```

## Common Errors

### Invalid prisma.yml file prisma.yml should NOT have additional properties.

You can read more about this error and how to fix it [in the Forum](https://www.graph.cool/forum/t/how-to-fix-invalid-prisma-yml-file-prisma-yml-should-not-have-additional-properties-additionalproperty-endpoint-errors/3303).
