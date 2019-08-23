package org.jetbrains.plugins.scala.lang.psi
package types
package api

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.plugins.scala.caches.RecursionManager
import org.jetbrains.plugins.scala.caches.stats.Tracer

import scala.util.DynamicVariable

/**
  * @author adkozlov
  */
trait Equivalence {
  typeSystem: TypeSystem =>

  import ConstraintsResult.Left
  import TypeSystem._

  private val guard = RecursionManager.RecursionGuard[Key](s"${typeSystem.name}.equivalence.guard")

  private val cache = ContainerUtil.newConcurrentMap[Key, ConstraintsResult]()

  private val eval = new DynamicVariable(false)

  final def equiv(left: ScType, right: ScType): Boolean = equivInner(left, right).isRight

  def clearCache(): Unit = cache.clear()

  /**
    * @param falseUndef use false to consider undef type equals to any type
    */
  final def equivInner(left: ScType, right: ScType,
                       constraints: ConstraintSystem = ConstraintSystem.empty,
                       falseUndef: Boolean = true): ConstraintsResult = {
    ProgressManager.checkCanceled()

    if (left == right) constraints
    else if (left.canBeSameClass(right)) {
      val result = equivInner(Key(left, right, falseUndef))
      combine(result)(constraints)
    } else Left
  }

  protected def equivComputable(key: Key): Computable[ConstraintsResult]

  private def equivInner(key: Key): ConstraintsResult = {
    val tracer = Tracer("Equivalence.equivInner", "Equivalence.equivInner")

    tracer.invocation()
    val nowEval = eval.value
    val fromCache =
      if (nowEval) None
      else eval.withValue(true) {
        Option(cache.get(key))
      }

    fromCache.orElse(
      guard.doPreventingRecursion(key) {
        val stackStamp = RecursionManager.markStack()

        tracer.calculationStart()
        val result = try {
          Option(equivComputable(key).compute())
        } finally {
          tracer.calculationEnd()
        }

        result.foreach(result =>
          if (!nowEval && stackStamp.mayCacheNow())
            eval.withValue(true) { cache.put(key, result) }
        )
        result
      }
    ).getOrElse(Left)
  }
}
