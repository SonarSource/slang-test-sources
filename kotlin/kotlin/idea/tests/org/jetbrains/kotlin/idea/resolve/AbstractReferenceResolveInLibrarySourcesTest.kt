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

package org.jetbrains.kotlin.idea.resolve

import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.navigation.NavigationTestUtils
import org.jetbrains.kotlin.test.InTextDirectivesUtils
import java.io.File
import org.jetbrains.kotlin.idea.test.PluginTestCaseBase
import org.junit.Assert
import junit.framework.AssertionFailedError

abstract class AbstractReferenceResolveInLibrarySourcesTest : KotlinLightCodeInsightFixtureTestCase() {
    companion object {
        private val REF_CARET_MARKER = "<ref-caret>"
    }

    fun doTest(path: String) {
        val fixture = myFixture!!

        fixture.configureByFile(path)

        val expectedResolveData = AbstractReferenceResolveTest.readResolveData(fixture.file!!.text, 0)

        val gotoData = NavigationTestUtils.invokeGotoImplementations(fixture.editor, fixture.file)!!
        Assert.assertEquals("Single target expected for origianl file", 1, gotoData.targets.size)

        val testedPsiElement = gotoData.targets[0].navigationElement
        val testedElementFile = testedPsiElement.containingFile!!

        val lineContext = InTextDirectivesUtils.findStringWithPrefixes(fixture.file!!.text, "CONTEXT:")
        if (lineContext == null) {
            throw AssertionFailedError("'CONTEXT: ' directive is expected to set up position in library file: ${testedElementFile.name}")
        }

        val inContextOffset = lineContext.indexOf(REF_CARET_MARKER)
        if (inContextOffset == -1) throw IllegalStateException("No '$REF_CARET_MARKER' marker found in 'CONTEXT: $lineContext'")

        val contextStr = lineContext.replace(REF_CARET_MARKER, "")
        val offsetInFile = testedElementFile.text!!.indexOf(contextStr)
        if (offsetInFile == -1) throw IllegalStateException("Context '$contextStr' wasn't found in file ${testedElementFile.name}")

        val offset = offsetInFile + inContextOffset

        val reference = testedElementFile.findReferenceAt(offset)!!

        AbstractReferenceResolveTest.checkReferenceResolve(expectedResolveData, offset, reference)
    }

    override fun getTestDataPath() : String = File(PluginTestCaseBase.getTestDataPathBase(), "/resolve/referenceInLib").path + File.separator
    override fun fileName(): String = getTestName(true) + ".kt"
}
