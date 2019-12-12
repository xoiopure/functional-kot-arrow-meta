package arrow.meta.quotes.classorobject

import arrow.meta.quotes.Scope
import arrow.meta.quotes.ScopedList
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtInitializerList
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry

/**
 * <code> TODO </code>
 *
 * A template destructuring [Scope] for a [KtEnumEntry].
 *
 *  ```
 * import arrow.meta.Meta
 * import arrow.meta.Plugin
 * import arrow.meta.invoke
 * import arrow.meta.quotes.Transform
 * import arrow.meta.quotes.thisExpression
 *
 * val Meta.reformatEnumEntry: Plugin
 *  get() =
 *   "Reformat Enum Entry" {
 *     meta(
 *       enumEntry({ true }) { classDeclaration ->
 *         Transform.replace(
 *           replacing = classDeclaration,
 *           newDeclaration = TODO()
 *         )
 *       }
 *     )
 *   }
 * ```
 */
class EnumEntry(
  override val value: KtEnumEntry,
  val superTypeListEntries: ScopedList<KtSuperTypeListEntry> = ScopedList(value = value.superTypeListEntries),
  val initializerList: Scope<KtInitializerList> = Scope(value.initializerList)
) : ClassDeclaration<KtEnumEntry>(value)