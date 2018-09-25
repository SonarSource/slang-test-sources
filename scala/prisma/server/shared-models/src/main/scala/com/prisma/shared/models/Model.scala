package com.prisma.shared.models

import com.prisma.shared.models.IdType.Id
import com.prisma.shared.models.Manifestations.ModelManifestation
import scala.language.implicitConversions

case class ModelTemplate(
    name: String,
    stableIdentifier: String,
    fieldTemplates: List[FieldTemplate],
    manifestation: Option[ModelManifestation]
) {
  def build(schema: Schema): Model = new Model(this, schema)
}

object Model {
  implicit def asModelTemplate(model: Model): ModelTemplate = model.template

  val empty: Model = new Model(
    template = ModelTemplate(name = "", stableIdentifier = "", fieldTemplates = List.empty, manifestation = None),
    schema = Schema.empty
  )
}
class Model(
    val template: ModelTemplate,
    val schema: Schema
) {
  import template._

  val dbName: String                                     = manifestation.map(_.dbName).getOrElse(name)
  lazy val fields: List[Field]                           = fieldTemplates.map(_.build(this))
  lazy val scalarFields: List[ScalarField]               = fields.collect { case f: ScalarField => f }
  lazy val scalarListFields: List[ScalarField]           = scalarFields.filter(_.isList)
  lazy val scalarNonListFields: List[ScalarField]        = scalarFields.filter(!_.isList)
  lazy val visibleScalarNonListFields: List[ScalarField] = scalarNonListFields.filter(_.isVisible)
  lazy val relationFields: List[RelationField]           = fields.collect { case f: RelationField => f }
  lazy val relationListFields: List[RelationField]       = relationFields.filter(_.isList)
  lazy val relationNonListFields: List[RelationField]    = relationFields.filter(!_.isList)
  lazy val visibleRelationFields: List[RelationField]    = relationFields.filter(_.isVisible)
  lazy val cascadingRelationFields: List[RelationField]  = relationFields.filter(field => field.relation.sideOfModelCascades(this))
  lazy val nonListFields                                 = fields.filter(!_.isList)
  lazy val idField                                       = getScalarFieldByName("id")
  lazy val idField_!                                     = getScalarFieldByName_!("id")
  lazy val dbNameOfIdField_!                             = idField_!.dbName
  lazy val updatedAtField                                = getFieldByName("updatedAt")
  lazy val hasVisibleIdField: Boolean                    = idField.exists(_.isVisible)

  def filterScalarFields(fn: ScalarField => Boolean): Model = {
    val newFields         = this.scalarFields.filter(fn).map(_.template)
    val newModel          = copy(fieldTemplates = newFields)
    val newModelsInSchema = schema.models.filter(_.name != name).map(_.template) :+ newModel
    schema.copy(modelTemplates = newModelsInSchema).getModelByName_!(name)
  }

  def getRelationFieldByName_!(name: String): RelationField   = getFieldByName_!(name).asInstanceOf[RelationField]
  def getScalarFieldByName_!(name: String): ScalarField       = getFieldByName_!(name).asInstanceOf[ScalarField]
  def getScalarFieldByName(name: String): Option[ScalarField] = getFieldByName(name).map(_.asInstanceOf[ScalarField])
  def getFieldByName_!(name: String): Field                   = getFieldByName(name).getOrElse(sys.error(s"field $name is not part of the model ${this.name}"))
  def getFieldByName(name: String): Option[Field]             = fields.find(_.name == name)

}
