package org.virtuslab.beholder.filters.forms

import org.virtuslab.beholder.filters.{ FilterFieldComponent, FilterRange }
import org.virtuslab.beholder.utils.ILikeExtension._
import org.virtuslab.beholder.utils.SeqParametersHelperComponent
import org.virtuslab.unicorn.UnicornWrapper
import play.api.data.Forms._
import play.api.data.format.Formatter
import play.api.data.validation.Constraint
import play.api.data.{ FormError, Mapping }
import slick.ast.{ BaseTypedType, TypedType }

trait FromFilterFieldsComponent extends FilterFieldComponent with SeqParametersHelperComponent {
  self: UnicornWrapper[Long] =>

  import unicorn._
  import unicorn.profile.api._

  abstract class FormFilterField[A: TypedType, B](mapping: Mapping[B]) extends MappedFilterField[A, B] {

    final def bind(name: String)(data: Map[String, String]): Either[Seq[FormError], B] = mapping.withPrefix(name).bind(data)

    final def unbind(name: String)(value: Any): Map[String, String] = mapping.withPrefix(name).unbind(value.asInstanceOf[B])
  }

  object FromFilterFields {

    private def ignoreMapping[T] = new Mapping[T] {
      val key = ""
      val mappings: Seq[Mapping[_]] = Nil
      val constraints: Seq[Constraint[T]] = Nil

      def bind(data: Map[String, String]): Either[Seq[FormError], T] = Left(Nil)

      def unbindAndValidate(value: T): (Map[String, String], Seq[FormError]) = Map[String, String]() -> Nil

      def withPrefix(prefix: String): Mapping[T] = this

      def verifying(constraints: Constraint[T]*): Mapping[T] = this

      override def unbind(value: T): Map[String, String] = Map[String, String]()
    }

    private def rangeMapping[T: Formatter] = mapping(
      "from" -> optional(of[T]),
      "to" -> optional(of[T]))(FilterRange.apply)(FilterRange.unapply)

    //API

    /**
     * search in text (ilike)
     */
    object inIntField extends FormFilterField[Int, Int](number) {
      override def filterOnColumn(column: Rep[Int])(data: Int): Rep[Option[Boolean]] = column.? === data
    }

    /**
     * check if value is in given sequence
     */
    object inIntFieldSeq extends FormFilterField[Int, Seq[Int]](seq(number)) {
      override protected def filterOnColumn(column: Rep[Int])(dataSeq: Seq[Int]): Rep[Option[Boolean]] = {
        SeqParametersHelper.isColumnValueInsideSeq(column)(dataSeq)((column, data) => column.? === data)
      }
    }

    /**
     * simple check boolean
     */
    object inBoolean extends FormFilterField[Boolean, Boolean](boolean) {
      override def filterOnColumn(column: Rep[Boolean])(data: Boolean): Rep[Option[Boolean]] = column.? === data
    }

    /**
     * search in text (ilike)
     */
    object inText extends FormFilterField[String, String](text) {
      override def filterOnColumn(column: Rep[String])(data: String): Rep[Option[Boolean]] = column.? ilike s"%${escape(data)}%"
    }

    /**
     * check if text is in given text sequence (ilike)
     */
    object inTextSeq extends FormFilterField[String, Seq[String]](seq(text)) {
      override def filterOnColumn(column: Rep[String])(dataSeq: Seq[String]): Rep[Option[Boolean]] = {
        SeqParametersHelper.isColumnValueInsideSeq(column)(dataSeq)((column, d) => column.? ilike s"%${escape(d)}%")
      }
    }

    object inBigDecimal extends FormFilterField[BigDecimal, BigDecimal](bigDecimal) {
      override def filterOnColumn(column: Rep[BigDecimal])(data: BigDecimal): Rep[Option[Boolean]] = column.? === data
    }

    /**
     * search in text (ilike) for optional fields
     */
    object inOptionText extends FormFilterField[Option[String], String](text) {
      override def filterOnColumn(column: Rep[Option[String]])(data: String): Rep[Option[Boolean]] = column ilike s"%${escape(data)}%"
    }

    /**
     * check enum value
     * @tparam T - enum class (eg. Colors.type)
     */
    def inEnum[T <: Enumeration](implicit tm: BaseTypedType[T#Value], formatter: Formatter[T#Value]): FormFilterField[T#Value, T#Value] =
      new FormFilterField[T#Value, T#Value](of[T#Value]) {
        override def filterOnColumn(column: Rep[T#Value])(data: T#Value): Rep[Option[Boolean]] = column.? === data
      }

    def inEnumSeq[T <: Enumeration](implicit tm: BaseTypedType[T#Value], formatter: Formatter[T#Value]): FormFilterField[T#Value, Seq[T#Value]] = {
      inFieldSeq[T#Value]
    }

    def inField[T](implicit tm: BaseTypedType[T], formatter: Formatter[T]): FormFilterField[T, T] =
      new FormFilterField[T, T](of[T]) {
        override def filterOnColumn(column: Rep[T])(data: T): Rep[Option[Boolean]] = column.? === data
      }

    def inFieldSeq[T](implicit tm: BaseTypedType[T], formatter: Formatter[T]): FormFilterField[T, Seq[T]] =
      new FormFilterField[T, Seq[T]](seq(of[T])) {
        override def filterOnColumn(column: Rep[T])(dataSeq: Seq[T]): Rep[Option[Boolean]] = {
          SeqParametersHelper.isColumnValueInsideSeq(column)(dataSeq)((column, data) => column.? === data)
        }
      }

    def inRange[T](implicit tm: BaseTypedType[T], f: Formatter[T]): FormFilterField[T, FilterRange[T]] =
      new FormFilterField[T, FilterRange[T]](rangeMapping[T]) {
        override def filterOnColumn(column: Rep[T])(value: FilterRange[T]): Rep[Option[Boolean]] = {
          value match {
            case FilterRange(Some(from), Some(to)) => column.? >= from && column.? <= to
            case FilterRange(None, Some(to)) => column.? <= to
            case FilterRange(Some(from), None) => column.? >= from
            case _ => LiteralColumn(Some(true))
          }
        }
      }

    /**
     * Search in range (form contain from and to).
     */
    def inOptionRange[T](implicit tm: BaseTypedType[T], f: Formatter[T]): FormFilterField[Option[T], FilterRange[T]] =
      new FormFilterField[Option[T], FilterRange[T]](rangeMapping[T]) {
        override def filterOnColumn(column: Rep[Option[T]])(value: FilterRange[T]): Rep[Option[Boolean]] = value match {
          case FilterRange(Some(from), Some(to)) => column >= from && column <= to
          case FilterRange(None, Some(to)) => column <= to
          case FilterRange(Some(from), None) => column >= from
          case _ => LiteralColumn(Some(true))
        }
      }

    /**
     * Ignores given field in filter.
     */
    def ignore[T: TypedType]: FormFilterField[T, T] = new FormFilterField[T, T](ignoreMapping[T]) {
      override def filterOnColumn(column: Rep[T])(value: T): Rep[Option[Boolean]] = LiteralColumn(Some(true))
    }
  }
}