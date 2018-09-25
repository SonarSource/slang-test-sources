package org.http4s
package bench

import org.openjdk.jmh.annotations._

@Fork(2)
@Threads(1)
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
class SchemeBench {
  @Benchmark
  def parseHttp =
    Uri.Scheme.parse("http")

  @Benchmark
  def parseHttps =
    Uri.Scheme.parse("https")

  @Benchmark
  def parseMailto =
    Uri.Scheme.parse("mailto")
}
