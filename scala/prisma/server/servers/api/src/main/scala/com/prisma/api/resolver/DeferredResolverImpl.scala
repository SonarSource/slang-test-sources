package com.prisma.api.resolver

import com.prisma.api.connector.DataResolver
import com.prisma.api.resolver.DeferredTypes._
import sangria.execution.deferred.{Deferred, DeferredResolver}

import scala.concurrent.{ExecutionContext, Future}

class DeferredResolverImpl[CtxType](dataResolver: DataResolver) extends DeferredResolver[CtxType] {

  val toManyDeferredResolver: ToManyDeferredResolver       = new ToManyDeferredResolver(dataResolver)
  val manyModelDeferredResolver: ManyModelDeferredResolver = new ManyModelDeferredResolver(dataResolver)
  val countManyModelDeferredResolver                       = new CountManyModelDeferredResolver(dataResolver)
  val toOneDeferredResolver                                = new ToOneDeferredResolver(dataResolver)
  val scalarListDeferredResolver                           = new ScalarListDeferredResolver(dataResolver)

  override def resolve(deferred: Vector[Deferred[Any]], ctx: CtxType, queryState: Any)(implicit ec: ExecutionContext): Vector[Future[Any]] = {

    // group orderedDeferreds by type
    val orderedDeferred = DeferredUtils.tagDeferredByOrder(deferred)

    val manyModelDeferreds = orderedDeferred.collect {
      case OrderedDeferred(deferred: ManyModelDeferred, order) =>
        OrderedDeferred(deferred, order)
    }

    val countManyModelDeferreds = orderedDeferred.collect {
      case OrderedDeferred(deferred: CountManyModelDeferred, order) =>
        OrderedDeferred(deferred, order)
    }

    val toManyDeferreds = orderedDeferred.collect {
      case OrderedDeferred(deferred: ToManyDeferred, order) =>
        OrderedDeferred(deferred, order)
    }

    val toOneDeferreds = orderedDeferred.collect {
      case OrderedDeferred(deferred: ToOneDeferred, order) =>
        OrderedDeferred(deferred, order)
    }

    val scalarListDeferreds = orderedDeferred.collect {
      case OrderedDeferred(deferred: ScalarListDeferred, order) =>
        OrderedDeferred(deferred, order)
    }

    // for every group, further break them down by their arguments
    val manyModelDeferredsMap      = DeferredUtils.groupModelDeferred[ManyModelDeferred](manyModelDeferreds)
    val countManyModelDeferredsMap = DeferredUtils.groupModelDeferred[CountManyModelDeferred](countManyModelDeferreds)
    val toManyDeferredsMap         = DeferredUtils.groupRelatedDeferred[ToManyDeferred](toManyDeferreds)
    val toOneDeferredMap           = DeferredUtils.groupRelatedDeferred[ToOneDeferred](toOneDeferreds)
    val scalarListDeferredsMap     = DeferredUtils.groupScalarListDeferreds(scalarListDeferreds)

    // for every group of deferreds, resolve them
    val manyModelFutureResults = manyModelDeferredsMap
      .map {
        case (_, value) => manyModelDeferredResolver.resolve(value)
      }
      .toVector
      .flatten

    val countManyModelFutureResults = countManyModelDeferredsMap
      .map {
        case (_, value) => countManyModelDeferredResolver.resolve(value)
      }
      .toVector
      .flatten

    val toManyFutureResults = toManyDeferredsMap
      .map {
        case (_, value) => toManyDeferredResolver.resolve(value)
      }
      .toVector
      .flatten

    val toOneFutureResults = toOneDeferredMap
      .map {
        case (_, value) => toOneDeferredResolver.resolve(value)
      }
      .toVector
      .flatten

    val scalarListFutureResult = scalarListDeferredsMap
      .map {
        case (_, value) => scalarListDeferredResolver.resolve(value)
      }
      .toVector
      .flatten

    val connectionFutureResult = orderedDeferred.collect {
      case OrderedDeferred(deferred: IdBasedConnectionDeferred, order) =>
        OrderedDeferredFutureResult(Future.successful(deferred.conn), order)
    }

    (manyModelFutureResults ++
      countManyModelFutureResults ++
      toManyFutureResults ++
      toOneFutureResults ++
      scalarListFutureResult ++
      connectionFutureResult).sortBy(_.order).map(_.future)
  }
}
