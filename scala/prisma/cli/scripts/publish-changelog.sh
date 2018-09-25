#!/bin/bash

#
# Build Order
# prisma-db-introspection
# prisma-yml
# prisma-cli-engine
# prisma-cli-core
# prisma-cli

set -e
set -x

if [[ $CIRCLE_TAG ]]; then
  echo "WARNING: CIRCLE_TAG is set to $CIRCLE_TAG. This will publish a new version on the @latest tag."
  sleep 5
else
  echo "INFO: This will deploy a new version on the @beta tag"
fi

if [[ $CIRCLE_COMPARE_URL ]]; then
  export lastCommits=`echo $CIRCLE_COMPARE_URL | sed -n 's/.*compare\/\(.*\)/\1/p' | sed 's/\.\.\./ /'`
else
  export lastCommits="HEAD"
fi

export changedFiles=$(git diff-tree --no-commit-id --name-only -r $lastCommits)

introspectionChanged=false
ymlChanged=false
coreChanged=false
engineChanged=false

if [[ "$changedFiles" = *"cli/packages/prisma-db-introspection"* ]]; then
  introspectionChanged=true
fi

if [[ "$changedFiles" = *"cli/packages/prisma-yml"* ]]; then
  ymlChanged=true
fi

if [[ "$changedFiles" = *"cli/packages/prisma-cli-core"* ]]; then
  coreChanged=true
fi

if [[ "$changedFiles" = *"cli/packages/prisma-cli-engine"* ]]; then
  engineChanged=true
fi

echo "introspection changed: $introspectionChanged yml changed: $ymlChanged. core changed: $coreChanged. engine changed: $engineChanged"

if [ $introspectionChanged == false ] && [ $ymlChanged == false ] && [ $coreChanged == false ] && [ $engineChanged == false ] && [ -z "$CIRCLE_TAG" ]; then
  echo "There are no changes in the CLI."
  exit 0;
fi

latestVersion=$(npm info prisma version)
tag=${CIRCLE_TAG:-$latestVersion}
tagElements=(${tag//./ })
nextDockerMinor=${tagElements[1]}
if [[ $CIRCLE_TAG ]]; then
  nextDockerTag="changelog"
else
  step=1
  nextDockerMinor=$((nextDockerMinor + step))
  nextDockerTag="changelog"
fi

node cli/scripts/waitUntilTagPublished.js $nextDockerTag

cd cli/packages/

export introspectionVersionBefore=$(cat prisma-db-introspection/package.json | jq -r '.version')

if [ $introspectionChanged ] || [ $CIRCLE_TAG ]; then
  cd prisma-db-introspection
  sleep 0.5
  ../../scripts/doubleInstall.sh
  yarn build
  if [[ $CIRCLE_TAG ]]; then
    npm version --allow-same-version $(npm info prisma-db-introspection version)-changelog
    # npm version patch --no-git-tag-version
    npm publish --tag changelog
  else
    npm version --allow-same-version $(npm info prisma-db-introspection version --tag beta)
    npm version prerelease --no-git-tag-version
    npm publish --tag changelog
  fi
  cd ..
fi
export introspectionVersion=$(cat prisma-db-introspection/package.json | jq -r '.version')


export ymlVersionBefore=$(cat prisma-yml/package.json | jq -r '.version')

if [ $ymlChanged ] || [ $CIRCLE_TAG ]; then
  echo "Going to publish yml"
  cd prisma-yml
  yarn install
  yarn build
  if [[ $CIRCLE_TAG ]]; then
    # make sure it is the latest version
    npm version --allow-same-version $(npm info prisma-yml version)-changelog
    # npm version patch --no-git-tag-version
    npm publish --tag changelog
  else
    npm version --allow-same-version $(npm info prisma-yml version --tag beta)
    npm version prerelease --no-git-tag-version
    npm publish --tag changelog
  fi
  yarn install
  cd ..
fi
export ymlVersion=$(cat prisma-yml/package.json | jq -r '.version')

if [ $ymlVersionBefore != $ymlVersion ] || [ $engineChanged ]; then
  cd prisma-cli-engine
  sleep 3.0
  yarn add prisma-yml@$ymlVersion
  sleep 0.2
  ../../scripts/doubleInstall.sh
  yarn build
  if [[ $CIRCLE_TAG ]]; then
    npm version --allow-same-version $(npm info prisma-cli-engine version)-changelog
    # npm version patch --no-git-tag-version
    npm publish --tag changelog
  else
    npm version --allow-same-version $(npm info prisma-cli-engine version --tag beta)
    npm version prerelease --no-git-tag-version
    npm publish --tag changelog
  fi
  cd ..
fi
export engineVersion=$(cat prisma-cli-engine/package.json | jq -r '.version')


if [ $ymlVersionBefore != $ymlVersion ] || [ $coreChanged ] || [ $introspectionChanged ]; then
  cd prisma-cli-core
  sleep 3.0
  yarn add prisma-yml@$ymlVersion
  sleep 0.2
  yarn add prisma-db-introspection@$introspectionVersion
  yarn install

  # new docker tag
  sed -i.bak "s/image: prismagraphql\/prisma:[0-9]\{1,\}\.[0-9]\{1,\}/image: prismagraphql\/prisma:$nextDockerTag/g" src/util.ts

  yarn build
  if [[ $CIRCLE_TAG ]]; then
    npm version --allow-same-version $(npm info prisma-cli-core version)-changelog
    # npm version patch --no-git-tag-version
    npm publish --tag changelog
  else
    npm version --allow-same-version $(npm info prisma-cli-core version --tag changelog)
    npm version prerelease --no-git-tag-version
    npm publish --tag changelog
  fi
  cd ..
fi
export coreVersion=$(cat prisma-cli-core/package.json | jq -r '.version')

cd prisma-cli
sleep 0.5
yarn add prisma-cli-engine@$engineVersion prisma-cli-core@$coreVersion
yarn install
yarn build

if [ -z "$CIRCLE_TAG" ]; then
  # latestBetaVersion=$(npm info prisma version --tag changelog)
  latestBetaVersion=1.9.0-changelog.0
  latestVersionElements=(${latestVersion//./ })
  latestBetaVersionElements=(${latestBetaVersion//./ })

  betaMinor=${latestBetaVersionElements[1]}
  latestMinor=${latestVersionElements[1]}
  latestMajor=${latestVersionElements[0]}

  betaLastNumber=`echo $latestBetaVersion | sed -n 's/.*changelog\.\([0-9]\{1,\}\)/\1/p'`

  echo "betaLastNumber $betaLastNumber"

  # calc next minor
  step=1
  nextMinor=$((latestMinor + step))

  nextLastNumber=0

  echo "beta minor $betaMinor latest minor $latestMinor next minor ${nextMinor}"

  # calc next last number
  if [ $betaMinor > $latestMinor ] && [ $betaMinor != $latestMinor ]; then
    echo "$betaMinor is greater than $latestMinor"
    nextLastNumber=$((betaLastNumber + step))
  fi

  newVersion="$latestMajor.$nextMinor.0-changelog.$nextLastNumber"
  echo "new version: $newVersion"

  npm version $newVersion
  npm publish --tag changelog
else
  newVersion=$CIRCLE_TAG

  echo "new version: $newVersion"
  npm version $newVersion
  npm publish --tag changelog
fi
