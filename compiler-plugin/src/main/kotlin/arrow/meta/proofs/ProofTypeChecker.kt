package arrow.meta.proofs

import arrow.meta.phases.CompilerContext
import arrow.meta.phases.resolve.`isSubtypeOf(NewKotlinTypeChecker)`
import arrow.meta.phases.resolve.baseLineTypeChecker
import arrow.meta.phases.resolve.typeProofs
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.isError

class ProofTypeChecker(private val compilerContext: CompilerContext) : KotlinTypeChecker {

  override fun isSubtypeOf(p0: KotlinType, p1: KotlinType): Boolean {
    return if (!p0.isError && !p1.isError) {
      val result = p0.`isSubtypeOf(NewKotlinTypeChecker)`(p1)
      val subTypes = if (!result && !p0.isError && !p1.isError) {
        compilerContext.module.typeProofs.hasProof(p0, p1)
      } else result
      println("typeConversion: $p0 : $p1 -> $subTypes")
      subTypes
    } else false
  }

  override fun equalTypes(p0: KotlinType, p1: KotlinType): Boolean {
    val result = baseLineTypeChecker.equalTypes(p0, p1)
    //println("typeConversion:equalTypes: $p0 : $p1 -> $result")
    return result
  }
}