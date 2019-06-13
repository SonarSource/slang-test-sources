# Repository location

https://github.com/SonarSource/slang-test-sources/

Git options:
```bash
git config --local core.autocrlf false
```

# Repository content

```bash
git clone --depth 1 --branch v1.7.13 git@github.com:kubernetes/kubernetes.git kubernetes
rm -rf kubernetes/.git
```
