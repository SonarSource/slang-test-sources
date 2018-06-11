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

package org.jetbrains.kotlin.types.checker;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.TypeConstructor;
import org.jetbrains.kotlin.types.TypeProjection;

class TypeCheckerProcedureCallbacksImpl implements TypeCheckingProcedureCallbacks {
    @Override
    public boolean assertEqualTypes(@NotNull KotlinType a, @NotNull KotlinType b, @NotNull TypeCheckingProcedure typeCheckingProcedure) {
        return typeCheckingProcedure.equalTypes(a, b);
    }

    @Override
    public boolean assertEqualTypeConstructors(@NotNull TypeConstructor a, @NotNull TypeConstructor b) {
        return a.equals(b);
    }

    @Override
    public boolean assertSubtype(@NotNull KotlinType subtype, @NotNull KotlinType supertype, @NotNull TypeCheckingProcedure typeCheckingProcedure) {
        return typeCheckingProcedure.isSubtypeOf(subtype, supertype);
    }

    @Override
    public boolean capture(@NotNull KotlinType type, @NotNull TypeProjection typeProjection) {
        return false;
    }

    @Override
    public boolean noCorrespondingSupertype(@NotNull KotlinType subtype, @NotNull KotlinType supertype) {
        return false; // type checking fails
    }
}
