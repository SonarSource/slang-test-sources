// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import com.microsoft.CNTK.SerializableFunction
import org.apache.spark.ml.param.{ComplexParam, Params}

/** Param for ByteArray.  Needed as spark has explicit params for many different
  * types but not ByteArray.
  */
class CNTKFunctionParam(parent: Params, name: String, doc: String,
                        isValid: SerializableFunction => Boolean)

  extends ComplexParam[SerializableFunction](parent, name, doc, isValid) {

  def this(parent: Params, name: String, doc: String) =
    this(parent, name, doc, {x => true})

}
