package com.prisma.deploy.validation

object NameConstraints {
  // TODO: a few of those won't be needed in the long run. Remove them when we are sure what we need.
  def isValidEnumValueName(name: String): Boolean = name.length <= 191 && name.matches("^[A-Z][a-zA-Z0-9_]*$")

  def isValidDataItemId(id: String): Boolean = id.length <= 25 && id.matches("^[a-zA-Z0-9\\-_]*$")

  def isValidFieldName(name: String): Boolean = name.length <= 64 && name.matches("^[a-z][a-zA-Z0-9]*$")

  def isValidEnumTypeName(name: String): Boolean = name.length <= 64 && name.matches("^[A-Z][a-zA-Z0-9_]*$")

  def isValidModelName(name: String): Boolean = name.length <= 64 && name.matches("^[A-Z][a-zA-Z0-9]*$")

  def isValidRelationName(name: String): Boolean = name.length <= 54 && name.matches("^[A-Z][a-zA-Z0-9_]*$")

  def isValidServiceName(name: String): Boolean = name.length <= 140 && name.matches("^[a-zA-Z][a-zA-Z0-9\\-_~]*$")

  def isValidServiceStage(stage: String): Boolean = stage.length <= 30 && stage.matches("^[a-zA-Z][a-zA-Z0-9\\-_]*$")

  def isValidFunctionName(name: String): Boolean = 1 <= name.length && name.length <= 64 && name.matches("^[a-zA-Z0-9\\-_]*$")
}
