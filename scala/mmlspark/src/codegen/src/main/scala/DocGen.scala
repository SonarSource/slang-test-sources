// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark.codegen

import java.io.File
import org.apache.commons.lang3.StringUtils
import org.apache.commons.io.FilenameUtils._

import com.microsoft.ml.spark.FileUtilities._
import Config._

/** Generate .rst file for each Python file inorder to autogenerate API documentation.
  * This generation should be run before the __init__.py file is generated, and before the Python is zipped
  * into the zip file.
  */
object DocGen {

  protected def rstFileLines(modules: String): String = {
    s"""|Pyspark Library
        |===============
        |
        |.. toctree::
        |   :maxdepth: 4
        |
        |$modules
        |""".stripMargin
  }

  protected def contentsString(name: String): String =
    s"""|$name
        |${"=" * name.length ()}
        |
        |.. automodule:: $name
        |    :members:
        |    :undoc-members:
        |    :show-inheritance:
        |""".stripMargin

  def genRstFiles(): Unit = {
    // Generate a modules.rst file that lists all the .py files to be included in API documentation
    // Find the files to use: Must start with upper case letter, end in .py
    val pattern = "^[A-Z]\\w*[.]py$".r
    val moduleString = allFiles(pyDir, f => pattern.findFirstIn(f.getName).isDefined)
          .map(f => s"   ${getBaseName(f.getName)}\n").mkString("")
    writeFile(new File(pyDocDir, "modules.rst"), rstFileLines(moduleString))

    // Generate .rst file for each PySpark wrapper - for documentation generation
    allFiles(pyDir, f => pattern.findFirstIn(f.getName).isDefined)
        .foreach{x => writeFile(new File(pyDocDir, getBaseName(x.getName) + ".rst"),
          contentsString(getBaseName(x.getName)))
        }
  }

}
