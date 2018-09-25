# slang-test-sources

# scala
```
cd its/sources/scala

git clone --depth 1 --branch 1.0.0-rc0             git@github.com:awslabs/deequ.git
git clone --depth 1 --branch 1.3.3.18              git@github.com:yahoo/kafka-manager.git
git clone --depth 1 --branch 1.16.5                git@github.com:prisma/prisma.git
git clone --depth 1 --branch v0.18.18              git@github.com:http4s/http4s.git
git clone --depth 1 --branch v0.14                 git@github.com:Azure/mmlspark.git
git clone --depth 1 --branch 1.4.6                 git@github.com:linkerd/linkerd.git
git clone --depth 1 --branch 2.6.19                git@github.com:playframework/playframework.git
git clone --depth 1 --branch master                git@github.com:lw-lin/CoolplaySpark.git
git clone --depth 1 --branch r110-valle-dei-templi git@github.com:snowplow/snowplow.git
git clone --depth 1 --branch v1.4.0                git@github.com:typelevel/cats.git
git clone --depth 1 --branch 0.9.0-incubating      git@github.com:apache/incubator-openwhisk.git
git clone --depth 1 --branch master                git@github.com:freechipsproject/rocket-chip.git
git clone --depth 1 --branch 4.28.0                git@github.com:gitbucket/gitbucket.git
git clone --depth 1 --branch finagle-18.9.0        git@github.com:twitter/finagle.git
git clone --depth 1 --branch v1.2                  git@github.com:alessandrolulli/reforest.git

find ../../sources/scala -type d -name ".git" -exec rm -rf "{}" \;
find ../../sources/scala -type f \
   -name "*.js"     -o -name "*.sql"   -o -name "*.py"    -o -name "*.swift" -o \
   -name "*.ts"     -o -name "*.xml"   -o -name "*.java"  -o -name "*.css"   -o \
   -name "*.css_t"  -o -name "*.zip"   -o -name "*.bz2"   -o -name "*.png"   -o \
   -name "*.PNG"    -o -name "*.jpg"   -o -name "*.jar"   -o -name "*.gz"    -o -name "*.h"  | xargs -I{} rm "{}"


rm playframework/documentation/manual/gettingStarted/code/PlayConsole.scala
```
