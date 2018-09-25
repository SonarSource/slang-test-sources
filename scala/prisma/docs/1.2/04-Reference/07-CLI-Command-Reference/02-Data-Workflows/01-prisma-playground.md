---
alias: anaif5iez3
description: Open the GraphQL Playground
---

# `prisma playground`

Open a [GraphQL Playground](https://github.com/graphcool/graphql-playground) for the current service. By default, this open the Desktop version of the Playground (if installed). The browser-based Playground can be forced by passing the `--web` flag.

#### Usage

```sh
prisma playground [flags]
```

#### Flags

```
--dotenv DOTENV          Path to .env file to inject env vars
-w, --web                Open browser-based Playground
```

#### Examples

##### Open Playground (Desktop version, if installed)

```sh
prisma playground
```

##### Open Playground (browser-based version)

```sh
prisma playground --web
```