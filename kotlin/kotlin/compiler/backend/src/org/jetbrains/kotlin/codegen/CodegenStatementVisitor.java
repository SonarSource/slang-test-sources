/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.*;

public class CodegenStatementVisitor extends KtVisitor<StackValue, StackValue> {
    private final ExpressionCodegen codegen;

    public CodegenStatementVisitor(ExpressionCodegen codegen) {
        this.codegen = codegen;
    }

    @Override
    public StackValue visitKtElement(@NotNull KtElement element, StackValue receiver) {
        return element.accept(codegen, receiver);
    }

    @Override
    public StackValue visitIfExpression(@NotNull KtIfExpression expression, StackValue receiver) {
        return codegen.generateIfExpression(expression, true);
    }

    @Override
    public StackValue visitTryExpression(@NotNull KtTryExpression expression, StackValue data) {
        return codegen.generateTryExpression(expression, true);
    }

    @Override
    public StackValue visitNamedFunction(@NotNull KtNamedFunction function, StackValue data) {
        return codegen.visitNamedFunction(function, data, true);
    }

    @Override
    public StackValue visitWhenExpression(@NotNull KtWhenExpression expression, StackValue data) {
        return codegen.generateWhenExpression(expression, true);
    }

    @Override
    public StackValue visitBlockExpression(@NotNull KtBlockExpression expression, StackValue data) {
        return codegen.generateBlock(expression, true);
    }

    @Override
    public StackValue visitLabeledExpression(@NotNull KtLabeledExpression expression, StackValue receiver) {
        KtExpression baseExpression = expression.getBaseExpression();
        assert baseExpression != null : "Label expression should have base one: " + expression.getText();
        return baseExpression.accept(this, receiver);
    }
}
