package ru.circumflex.orm

import _root_.ru.circumflex.core._
import java.lang.String

// ## Validation

case class ValidationError(val source: String,
                           val errorKey: String,
                           var params: Map[String, String]) {
  def this(source: String, errorKey: String, params: Pair[String, String]*) =
    this(source, errorKey, Map(params: _*))

  val fullKey = source + "." + errorKey
  params += "src" -> source

  protected def toMsg(key: String, messages: Messages): String = messages.get(key, params) match {
    case Some(msg) => msg
    case _ =>
      val i = key.indexOf(".")
      if (i == -1) key
      else toMsg(key.substring(i + 1), messages)
  }
  def toMsg(messages: Messages): String = toMsg(fullKey, messages)
  def toMsg: String = toMsg(CircumflexContext.get.messages)

  protected def matches(thisKey: String, key: String): Boolean =
    if (thisKey == key || thisKey + "." + errorKey == key) true
    else {
      val i = thisKey.indexOf(".")
      if (i == -1) false
      else matches(thisKey.substring(i + 1), key)
    }

  def matches(key: String): Boolean = matches(source, key)

  override def hashCode = source.hashCode * 31 + errorKey.hashCode
  override def equals(that: Any) = that match {
    case e: ValidationError => e.source == this.source && e.errorKey == this.errorKey
    case _ => false
  }

  override def toString = fullKey
}

trait ValidationErrorGroup extends HashModel {
  def errors: Seq[ValidationError]
  def get(key: String): Option[Seq[ValidationError]] = Some(errors.filter(e => e.matches(key)))
  override def toString = errors.map(_.toString).mkString(", ")
}

class ValidationErrors(var errors: ValidationError*) extends ValidationErrorGroup {
  def toException = new ValidationException(errors: _*)
}

class ValidationException(val errors: ValidationError*)
    extends CircumflexException("Validation failed.") with ValidationErrorGroup

class RecordValidator[R <: Record[R]] {
  protected var _validators: Seq[R => Option[ValidationError]] = Nil
  def validators = _validators
  def validate(record: R): Seq[ValidationError] =
    _validators.flatMap(_.apply(record)).toList.removeDuplicates
  def add(validator: R => Option[ValidationError]): this.type = {
    _validators ++= List(validator)
    return this
  }
  def addForInsert(validator: R => Option[ValidationError]): this.type =
    add(r => if (r.transient_?) validator(r) else None)
  def addForUpdate(validator: R => Option[ValidationError]): this.type =
    add(r => if (!r.transient_?) validator(r) else None)
  def notNull(f: R => Field[_]): this.type = add(r => {
    val field = f(r)
    if (field.null_?) Some(new ValidationError(field.uuid, "null")) else None
  })
  def notEmpty(f: R => TextField): this.type = add(r => {
    val field = f(r)
    if (field.null_?)
      Some(new ValidationError(field.uuid, "null"))
    else if (field.getValue.trim == "")
      Some(new ValidationError(field.uuid, "empty"))
    else None
  })
  def pattern(f: R => TextField, regex: String, key: String = "pattern"): this.type = add(r => {
    val field = f(r)
    if (field.null_?)
      None
    else if (!field.getValue.matches(regex))
      Some(new ValidationError(field.uuid, key, "regex" -> regex, "value" -> field.getValue))
    else None
  })
  def unique(f: R => Field[_], key: String = "unique"): this.type = addForInsert(r => {
    val field = f(r)
    r.relation.criteria.add(field EQ field()).unique.map(a => new ValidationError(field.uuid, key))
  })
  def uniqueAll(f: R => Seq[Field[_]], key: String = "unique"): this.type = addForInsert(r => {
    val fields = f(r)
    val crit = r.relation.criteria
    fields.foreach(f => crit.add(f EQ f()))
    crit.unique.map(a => new ValidationError(r.uuid, key))
  })
}



