# Repository location

https://github.com/SonarSource/slang-test-sources/

Git options:
```bash
git config --local core.autocrlf false
```

# Repository content

Option `core.symlinks=false` disable symbolic links when cloning, in order to guarantee same behavior in all OS.

```bash
git clone -c core.symlinks=false --depth 1 --branch v1.7.13 git@github.com:kubernetes/kubernetes.git kubernetes
rm -rf kubernetes/.git
```

For references, files subject to symblink removed from default clone:
```
kubernetes/.bazelrc
kubernetes/BUILD.bazel
kubernetes/Makefile
kubernetes/Makefile.generated_files
kubernetes/WORKSPACE
kubernetes/cluster/gce/cos
kubernetes/cluster/gce/ubuntu
kubernetes/cluster/juju/layers/kubernetes-master/actions/namespace-delete
kubernetes/cluster/juju/layers/kubernetes-master/actions/namespace-list
kubernetes/vendor/k8s.io/apiextensions-apiserver
kubernetes/vendor/k8s.io/apimachinery
kubernetes/vendor/k8s.io/apiserver
kubernetes/vendor/k8s.io/client-go
kubernetes/vendor/k8s.io/kube-aggregator
kubernetes/vendor/k8s.io/metrics
kubernetes/vendor/k8s.io/sample-apiserver
```