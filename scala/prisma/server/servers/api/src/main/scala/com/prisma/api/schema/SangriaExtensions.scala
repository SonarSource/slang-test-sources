package com.prisma.api.schema

import com.prisma.api.connector.SelectedFields
import com.prisma.api.schema.SangriaExtensions.ContextExtensions
import com.prisma.shared.models.Model
import sangria.ast.Selection
import sangria.schema.Context

trait SangriaExtensions {
  implicit def context2Extensions(ctx: Context[_, _]) = new ContextExtensions(ctx)
}

object SangriaExtensions {
  class ContextExtensions(val ctx: Context[_, _]) extends AnyVal {
    def getSelectedFields(model: Model): SelectedFields = {
      val currentFields  = ctx.astFields.filter(_.name == ctx.field.name)
      val selectedFields = recurse(model, currentFields.flatMap(_.selections)) ++ model.idField
      SelectedFields(selectedFields.toSet)
    }

    private def recurse(model: Model, selections: Vector[Selection]): Vector[com.prisma.shared.models.Field] = selections.flatMap {
      case astField: sangria.ast.Field =>
        model.getFieldByName(astField.name) ++ recurse(model, astField.selections)

      case fragmentSpread: sangria.ast.FragmentSpread =>
        val fragment = ctx.query.fragments(fragmentSpread.name)
        recurse(model, fragment.selections)

      case inlineFragment: sangria.ast.InlineFragment =>
        if (inlineFragment.typeCondition.forall(_.name == model.name)) {
          recurse(model, inlineFragment.selections)
        } else {
          Vector.empty
        }
    }
  }
}
