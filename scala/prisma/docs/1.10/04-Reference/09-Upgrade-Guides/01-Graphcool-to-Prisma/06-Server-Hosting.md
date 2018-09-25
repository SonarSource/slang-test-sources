---
alias: thohk5ovul
description: Server Hosting
---

# Server Hosting

When building a GraphQL server based on Prisma, you need to host the GraphQL server to make its functionality available to client applications.

<InfoBox type=warning>

When hosting your GraphQL server (e.g. with Zeit Now, AWS Lambda or some other hosting provider), you should ensure that it is deployed to the same _region_ as your Prisma service to ensure highest performance.

<InfoBox>

## Deployment with Zeit Now

[Now](https://zeit.co/now) is a one-click deployment tool for web applications. You can find a comprehensive tutorial explaining how to deploy and host your Prisma-based GraphQL server with Now [here](!alias-ahs1jahkee).

## Deployment with Apex Up

[Apex Up](https://up.docs.apex.sh) allows you to deploy traditional web servers to AWS Lambda.

## Deployment with the Serverless Framework

Another option to deploy your GraphQL server is by simply using a serverless functions provider, such as AWS Lambda, Google Cloud or Microsoft Azure. The easiest way to do so is by using the [Serverless Framework](https://serverless.com/).

<InfoBox type=warning>

Deploying your GraphQL server using a serverless functions provider is only possible if your clients are not using GraphQL subscriptions for realtime functionality. The reason for that is that subscriptions require the web server to _maintain state_ (because the server needs to remember which clients are subscribed to which events and retain an open connection to these clients). This is not possible with serverless functions.

</InfoBox>

To get started with the Serverless Framework, you need to install the CLI and sign up:

```sh
npm install -g serverless
serverless login
```

Depending on your serverless functions provider, you can then follow instructions from the [quickstart documentation](https://serverless.com/framework/docs/getting-started/) of the Serverless Framework:

* [AWS Lambda](https://serverless.com/framework/docs/providers/aws/guide/quick-start/)
* [Microsoft Azure](https://serverless.com/framework/docs/providers/azure/guide/quick-start/)
* [IBM Open Whisk](https://serverless.com/framework/docs/providers/openwhisk/guide/quick-start/)
* [Google Cloud Platform](https://serverless.com/framework/docs/providers/google/guide/quick-start/)
* [Kubeless](https://serverless.com/framework/docs/providers/kubeless/guide/quick-start/)
* [Spotinst](https://serverless.com/framework/docs/providers/spotinst/guide/quick-start/)
* [Webtasks](https://serverless.com/framework/docs/providers/webtasks/guide/quick-start/)
