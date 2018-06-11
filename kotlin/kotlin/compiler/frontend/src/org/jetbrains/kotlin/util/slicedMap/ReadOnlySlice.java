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

package org.jetbrains.kotlin.util.slicedMap;

import org.jetbrains.annotations.NotNull;

public interface ReadOnlySlice<K, V> {
    @NotNull
    KeyWithSlice<K, V, ? extends ReadOnlySlice<K, V>> getKey();

    V computeValue(SlicedMap map, K key, V value, boolean valueNotFound);

    /**
     * @return a slice that only retrieves the value from the storage and skips any computeValue() calls
     */
    ReadOnlySlice<K, V> makeRawValueVersion();
}
