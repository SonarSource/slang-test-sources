// "Create parameter 'foo'" "false"
// ACTION: Convert property initializer to getter
// ACTION: Create property 'foo'
// ACTION: Rename reference
// ERROR: Unresolved reference: foo

val test: Int = <caret>foo